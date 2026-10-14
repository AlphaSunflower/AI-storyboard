package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.security.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页搜索与抓取工具：{@code web_search} 与 {@code web_fetch}。
 *
 * <p>搜索后端是 <b>DeepSeek 原生网页搜索</b>，移植自 DSH 的
 * {@code @deepseek-ai/dsh-web-search-deepseek}：一次 Anthropic 兼容 Messages 调用
 * （{@code POST {base-url}/messages}）携带服务端工具 {@code web_search_20250305}，
 * 结果只从结构化的 {@code web_search_tool_result} 块与 {@code text} 块的 {@code citations}
 * 里解析，<b>绝不从模型文本里抓 URL</b>；响应里没有该块就直接报错，不降级成文本抓取。
 *
 * <p>{@code web_fetch} 在服务端直接抓取，因此必须先过 {@link OutboundUrlGuard}，
 * 阻断 loopback / 私网 / 链路本地地址（SSRF）；抓到的 HTML 统一转 Markdown
 * （{@link WebHtmlMarkdown}），避免把整页标签塞进模型上下文。
 *
 * <p>两个工具都通过 {@code AgentScopeToolPipeline} 注册，因此和自研工具一样进入工具审计。
 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeWebTools {

    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";

    /** 服务端网页搜索工具类型，Anthropic Messages 协议常量。 */
    static final String NATIVE_SEARCH_TOOL_TYPE = "web_search_20250305";
    /** {@code anthropic-version} 请求头，与 DSH 保持一致。 */
    static final String ANTHROPIC_VERSION = "2023-06-01";
    /** 归属标记：便于上游按客户端区分流量。 */
    static final String USER_AGENT = "moon-agent-web/1.0";

    static final int MAX_FETCH_CHARS = 20_000;
    static final int HARD_FETCH_CHARS = 100_000;
    /** 抓取响应体硬上限（2 MiB）：防止单个页面把堆撑爆，截断由字符上限兜底。 */
    static final int MAX_FETCH_BYTES = 2 * 1024 * 1024;
    /** 判定「不能当文本读」的响应类型前缀。 */
    private static final List<String> BINARY_TYPE_MARKERS = List.of(
            "image/", "audio/", "video/", "application/pdf", "application/zip",
            "application/octet-stream", "application/x-");
    private static final List<String> HTML_TYPE_MARKERS = List.of("text/html", "application/xhtml");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentScopeProperties properties;
    private final HttpClient http;

    /** 成功搜索结果缓存：key → (结果, 写入纳秒时间)。 */
    private final ConcurrentHashMap<String, CacheEntry> searchCache = new ConcurrentHashMap<>();
    /** single-flight：同一 key 的并发搜索共用一次厂商调用。 */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> inFlight =
            new ConcurrentHashMap<>();

    @Autowired
    public AgentScopeWebTools(AgentScopeProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    AgentScopeWebTools(AgentScopeProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** 配置了 URL 与 key 才注册；未配置时 {@link TurnCapabilityReporter} 会如实上报不可用。 */
    public List<AgentScopeTool> tools() {
        AgentScopeProperties.WebSearch config = properties.getWebSearch();
        if (!config.isConfigured()) return List.of();
        return List.of(webSearch(config), webFetch(config));
    }

    /** 网页检索能力是否真的可用（bean 存在 ≠ 已配置，能力上报必须看这个）。 */
    public boolean configured() {
        return properties.getWebSearch().isConfigured();
    }

    private AgentScopeTool webSearch(AgentScopeProperties.WebSearch config) {
        return new AgentScopeTool(
                WEB_SEARCH,
                "Search the web for current information. Returns an optional summary answer plus "
                        + "source URLs with titles, snippets and publish dates. Follow up with "
                        + "web_fetch when you need the full content of a specific result, and cite "
                        + "the relevant URLs as markdown links.",
                AgentScopeTool.schema(Map.of(
                        "query", Map.of("type", "string"),
                        "max_results", Map.of("type", "integer",
                                "minimum", 1, "maximum", 10)),
                        List.of("query")),
                true, true, false,
                (input, param) -> search(
                        config,
                        string(input.get("query")),
                        integer(input.get("max_results"), config.getMaxResults())));
    }

    private AgentScopeTool webFetch(AgentScopeProperties.WebSearch config) {
        return new AgentScopeTool(
                WEB_FETCH,
                "Fetch content from a public HTTP(S) URL and return it as Markdown (HTML pages are "
                        + "converted, long content is truncated). Internal, loopback, private "
                        + "addresses and redirects are rejected.",
                AgentScopeTool.schema(Map.of(
                        "url", Map.of("type", "string"),
                        "max_chars", Map.of("type", "integer")),
                        List.of("url")),
                true, true, false,
                (input, param) -> fetch(config, string(input.get("url")),
                        integer(input.get("max_chars"), MAX_FETCH_CHARS)));
    }

    // ---------------------------------------------------------------- web_search

    private Map<String, Object> search(AgentScopeProperties.WebSearch config,
                                       String query,
                                       int maxResults) {
        if (query.isBlank()) {
            return Map.of("ok", false, "error", "query is required");
        }
        int limit = clampResults(config, maxResults);
        String key = cacheKey(query, limit);
        Map<String, Object> cached = cachedSearch(config, key);
        if (cached != null) {
            return cached;
        }
        // single-flight：同一 key 的并发搜索只打一次上游，其余等待共享结果。
        // 原生搜索是完整模型轮次，重复调用既贵又慢，这里必须合并。
        CompletableFuture<Map<String, Object>> created = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return awaitShared(existing);
        }
        try {
            Map<String, Object> result = searchUncached(config, query, limit);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                storeSearch(config, key, result);
            }
            created.complete(result);
            return result;
        } catch (RuntimeException error) {
            Map<String, Object> failure = Map.of("ok", false,
                    "error", "web_search failed: " + rootMessage(error));
            created.complete(failure);
            return failure;
        } finally {
            inFlight.remove(key, created);
        }
    }

    /**
     * 按配置的后端分发一次搜索。信封形状两个后端完全一致，因此模型侧无需知道后端差异。
     */
    private Map<String, Object> searchUncached(AgentScopeProperties.WebSearch config,
                                               String query,
                                               int limit) {
        return config.getProvider() == AgentScopeProperties.WebSearch.Provider.SEARXNG
                ? searchSearxng(config, query, limit)
                : searchDeepseek(config, query, limit);
    }

    /**
     * 自托管 SearXNG：一次 {@code POST {searxng-url}/search}（form 参数 + {@code format=json}）。
     * 所有计算在自己机器上，没有第三方 key、没有按次计费。
     *
     * <p>注意 SearXNG 默认**关闭** json 输出：实例的 {@code settings.yml} 里
     * {@code search.formats} 必须含 {@code json}，否则这里会拿到 403 —— 这是自托管最常见的踩坑点，
     * 所以 403 会被翻译成带配置指引的文案，而不是一句 "HTTP 403"。
     */
    private Map<String, Object> searchSearxng(AgentScopeProperties.WebSearch config,
                                              String query,
                                              int limit) {
        try {
            List<String> pairs = new ArrayList<>();
            pairs.add("q=" + urlEncode(query));
            pairs.add("format=json");
            if (!config.getSearxngLanguage().isBlank()) {
                pairs.add("language=" + urlEncode(config.getSearxngLanguage()));
            }
            String body = String.join("&", pairs);

            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getSearxngEndpoint()))
                    .timeout(config.getTimeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                return Map.of("ok", false, "error",
                        "web_search failed: SearXNG refused format=json (HTTP 403). Enable it in the "
                                + "instance settings.yml: search: formats: [html, json]");
            }
            if (response.statusCode() >= 400) {
                return Map.of("ok", false,
                        "error", "web_search failed: SearXNG returned HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body() == null ? "{}" : response.body());
            return searxngEnvelope(query, limit, root);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "web_search failed: " + rootMessage(error));
        }
    }

    /**
     * 把 SearXNG 的 JSON 映射成工具结果。
     *
     * <p>SearXNG 已经把多引擎结果去重、打分、排序过了，这里只按顺序取前 limit 条；
     * 每条主结果的摘要字段是 {@code content}（历史别名 {@code snippet}/{@code description} 也兼容），
     * 日期字段是 {@code publishedDate}。一条主结果都没有时不报失败，而是让模型去细化 query ——
     * 这与上游行为一致，也不该把"没搜到"当成工具故障。
     */
    private Map<String, Object> searxngEnvelope(String query, int limit, JsonNode root) {
        JsonNode items = root.path("results");
        if (!items.isArray()) {
            return Map.of("ok", false, "error",
                    "web_search failed: SearXNG response has no results array "
                            + "(check that the instance returns JSON)");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> suggestions = textArray(root.path("suggestions"));
        List<String> answers = searxngAnswers(root.path("answers"));
        for (JsonNode item : items) {
            String url = item.path("url").asText("");
            if (url.isBlank() || !seen.add(url)) continue;
            if (results.size() >= limit) break;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", item.path("title").asText(""));
            entry.put("url", url);
            String snippet = firstText(item, "content", "snippet", "description");
            entry.put("snippet", trim(snippet, 1_200));
            String publishedAt = item.path("publishedDate").asText("");
            if (!publishedAt.isBlank()) entry.put("publishedAt", publishedAt);
            List<String> engines = textArray(item.path("engines"));
            if (!engines.isEmpty()) entry.put("engines", engines);
            results.add(entry);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("query", query);
        envelope.put("provider", "searxng");
        envelope.put("results", results);
        envelope.put("count", results.size());
        envelope.put("truncated", items.size() > results.size());
        if (!answers.isEmpty()) envelope.put("answer", trim(String.join("\n", answers), 4_000));
        if (!suggestions.isEmpty()) envelope.put("suggestions", suggestions);
        envelope.put("note", results.isEmpty()
                ? "No results. Refine the query or rephrase it and search again."
                : "Cite the relevant URLs above as markdown links in your answer.");
        return envelope;
    }

    /** 数组字段转字符串列表，非数组或空项一律忽略。 */
    private static List<String> textArray(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText("") : item.path("text").asText("");
            if (!value.isBlank()) values.add(value.strip());
        }
        return values;
    }

    /** SearXNG 的 {@code answers} 既可能是字符串数组，也可能是 {@code {answer: ...}} 数组。 */
    private static List<String> searxngAnswers(JsonNode node) {
        List<String> values = textArray(node);
        if (!values.isEmpty()) return values;
        if (!node.isArray()) return List.of();
        List<String> answers = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.path("answer").asText("");
            if (!value.isBlank()) answers.add(value.strip());
        }
        return answers;
    }

    /** 按顺序取第一个非空字段。 */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value.strip();
        }
        return "";
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * DeepSeek 原生搜索：一次 Anthropic 兼容 Messages 调用。请求体与 DSH 的 provider 逐字段对齐
     * （model / max_tokens / 单条 user 文本 / 服务端工具），因此任何拒绝都会带着上游可读文案回到模型。
     */
    private Map<String, Object> searchDeepseek(AgentScopeProperties.WebSearch config,
                                               String query,
                                               int limit) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.getModel());
            payload.put("max_tokens", config.getMaxTokens());
            payload.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", "Perform a web search for the query: " + query)))));
            payload.put("tools", List.of(Map.of(
                    "type", NATIVE_SEARCH_TOOL_TYPE,
                    "name", WEB_SEARCH,
                    "max_uses", config.getMaxUses())));

            URI endpoint = URI.create(config.getMessagesEndpoint());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(config.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    // 官方 DeepSeek 认 x-api-key，Anthropic 兼容代理多认 Bearer；两边都发，任一即可解析。
                    .header("x-api-key", config.getApiKey())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false,
                        "error", "web_search failed: " + httpError(response));
            }
            JsonNode root = JSON.readTree(response.body() == null ? "{}" : response.body());
            return searchEnvelope(query, limit, root);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "web_search failed: " + rootMessage(error));
        }
    }

    /**
     * 把 Messages 响应映射成工具结果。只接受结构化结果块；没有
     * {@code web_search_tool_result} 说明这次请求根本没触发原生搜索（模型没调工具、
     * 或上游不支持），必须显式失败而不是把模型散文当搜索结果。
     *
     * @param query 原始查询，回显给模型便于多轮对照
     * @param limit 本次调用的返回条数上限（seam 层截断，协议没有条数旋钮）
     * @param root  已解析的 Messages 响应
     * @return 工具结果信封
     */
    private Map<String, Object> searchEnvelope(String query, int limit, JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            return Map.of("ok", false, "error",
                    "web_search failed: " + providerError(root));
        }
        List<JsonNode> resultBlocks = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        // url → 引用摘录：web_search_result 条目本身通常没有摘要，摘录在 text 块的 citations 里。
        Map<String, String> citations = new LinkedHashMap<>();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("web_search_tool_result".equals(type)) {
                resultBlocks.add(block);
                continue;
            }
            if (!"text".equals(type)) continue;
            String text = block.path("text").asText("");
            if (!text.isBlank()) {
                if (answer.length() > 0) answer.append("\n\n");
                answer.append(text.strip());
            }
            for (JsonNode citation : block.path("citations")) {
                String url = citation.path("url").asText("");
                String cited = citation.path("cited_text").asText("");
                if (!url.isBlank() && !cited.isBlank()) citations.putIfAbsent(url, cited);
            }
        }
        if (resultBlocks.isEmpty()) {
            return Map.of("ok", false, "error",
                    "web_search failed: " + providerError(root));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int total = 0;
        for (JsonNode block : resultBlocks) {
            JsonNode items = block.path("content");
            if (!items.isArray()) continue;
            for (JsonNode item : items) {
                if (!"web_search_result".equals(item.path("type").asText(""))) continue;
                String url = item.path("url").asText("");
                if (url.isBlank() || !seen.add(url)) continue;
                total++;
                if (results.size() >= limit) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("title", item.path("title").asText(""));
                entry.put("url", url);
                String snippet = citations.getOrDefault(url, "").strip();
                entry.put("snippet", trim(snippet, 1_200));
                String publishedAt = item.path("page_age").asText("");
                if (!publishedAt.isBlank()) entry.put("publishedAt", publishedAt);
                results.add(entry);
            }
        }
        if (results.isEmpty()) {
            return Map.of("ok", false, "error",
                    "web_search failed: DeepSeek returned no web_search_result entries; "
                            + "the query may not have matched any page");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("query", query);
        envelope.put("provider", "deepseek");
        envelope.put("results", results);
        envelope.put("count", results.size());
        envelope.put("truncated", total > results.size());
        if (answer.length() > 0) envelope.put("answer", trim(answer.toString(), 4_000));
        envelope.put("note", "Cite the relevant URLs above as markdown links in your answer.");
        return envelope;
    }

    // ---------------------------------------------------------------- web_fetch

    private Map<String, Object> fetch(AgentScopeProperties.WebSearch config,
                                      String url,
                                      int maxChars) {
        if (url.isBlank()) {
            return Map.of("ok", false, "error", "url is required");
        }
        try {
            OutboundUrlGuard.requireSafeHttpUrl(url);
        } catch (RuntimeException rejected) {
            return Map.of("ok", false, "error", rootMessage(rejected));
        }
        int limit = Math.max(1, Math.min(HARD_FETCH_CHARS,
                maxChars <= 0 ? MAX_FETCH_CHARS : maxChars));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(config.getTimeout())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                            + "text/plain;q=0.8,application/json;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                // 不跟随跳转：跟随会绕过上面的 SSRF 守卫（第一跳公网、第二跳私网）。
                String location = response.headers().firstValue("location").orElse("");
                return Map.of("ok", false, "error",
                        "web_fetch refused to follow HTTP " + response.statusCode()
                                + " redirect" + (location.isBlank() ? "" : " to " + location)
                                + "; call web_fetch again with the final URL");
            }
            if (response.statusCode() >= 400) {
                return Map.of("ok", false,
                        "error", "web_fetch returned HTTP " + response.statusCode());
            }

            byte[] raw = response.body() == null ? new byte[0] : response.body();
            boolean bodyTruncated = raw.length > MAX_FETCH_BYTES;
            if (bodyTruncated) raw = Arrays.copyOf(raw, MAX_FETCH_BYTES);
            String contentType = response.headers().firstValue("content-type").orElse("");
            String mediaType = mediaTypeOf(contentType);
            String body = new String(raw, charsetOf(contentType));
            String text;
            boolean converted = false;
            if (isHtml(mediaType)) {
                WebHtmlMarkdown.Result parsed = WebHtmlMarkdown.convert(body);
                text = parsed.markdown();
                converted = true;
            } else if (isBinary(mediaType)) {
                return Map.of(
                        "ok", true,
                        "status", response.statusCode(),
                        "contentType", mediaType,
                        "url", url.strip(),
                        "content", "[binary content omitted: " + mediaType + ", "
                                + raw.length + " bytes]");
            } else {
                text = body;
            }

            boolean truncated = text.length() > limit;
            String content = truncated ? text.substring(0, limit) + "\n...[truncated]" : text;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("ok", true);
            envelope.put("status", response.statusCode());
            envelope.put("url", url.strip());
            if (!mediaType.isBlank()) envelope.put("contentType", mediaType);
            if (converted) envelope.put("converted", "html->markdown");
            envelope.put("truncated", truncated || bodyTruncated);
            envelope.put("content", content);
            return envelope;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "web_fetch failed: " + rootMessage(error));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 上游 HTTP 失败：优先取错误体里的可读文案，取不到才退回状态码。 */
    private static String httpError(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().strip();
        if (!body.isEmpty()) {
            try {
                JsonNode root = JSON.readTree(body);
                String detail = providerError(root);
                if (!detail.isBlank()) return detail;
            } catch (Exception ignored) {
                // 非 JSON 错误体（网关 5xx/HTML 页）只损失文案，不改变失败事实。
            }
            return "HTTP " + response.statusCode() + ": " + trim(body, 300);
        }
        return "HTTP " + response.statusCode();
    }

    /** 从响应体里取可读错误文案；没有 error 结构时返回空串。 */
    private static String providerError(JsonNode root) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            if (error.isTextual()) return error.asText("");
            String message = error.path("message").asText("");
            if (!message.isBlank()) return message;
            String type = error.path("type").asText("");
            if (!type.isBlank()) return type;
        }
        String message = root.path("message").asText("");
        if (!message.isBlank()) return message;
        return "DeepSeek returned no web_search_tool_result blocks; the request may not have "
                + "triggered native web search";
    }

    private static int clampResults(AgentScopeProperties.WebSearch config, int requested) {
        int fallback = config.getMaxResults();
        int value = requested <= 0 ? fallback : requested;
        return Math.max(1, Math.min(10, value));
    }

    private static String mediaTypeOf(String contentType) {
        if (contentType == null) return "";
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return mediaType.toLowerCase(Locale.ROOT);
    }

    private static boolean isHtml(String mediaType) {
        if (mediaType.isBlank()) return true;
        return HTML_TYPE_MARKERS.stream().anyMatch(mediaType::contains);
    }

    private static boolean isBinary(String mediaType) {
        return BINARY_TYPE_MARKERS.stream().anyMatch(mediaType::startsWith);
    }

    /** 响应字符集：优先 {@code content-type} 的 {@code charset=}，否则按 UTF-8。 */
    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*\"?([\\w-]+)").matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (RuntimeException unsupported) {
                    // 未知字符集名不该让整次抓取失败，退回 UTF-8。
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static Map<String, Object> awaitShared(
            CompletableFuture<Map<String, Object>> shared) {
        try {
            return shared.join();
        } catch (CompletionException | CancellationException error) {
            return Map.of("ok", false,
                    "error", "web_search failed: concurrent request aborted");
        }
    }

    private static String cacheKey(String query, int limit) {
        return limit + "|" + query.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> cachedSearch(AgentScopeProperties.WebSearch config, String key) {
        CacheEntry entry = searchCache.get(key);
        if (entry == null) return null;
        if (System.nanoTime() - entry.storedAtNanos() > config.getCacheTtl().toNanos()) {
            searchCache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private void storeSearch(AgentScopeProperties.WebSearch config,
                             String key,
                             Map<String, Object> value) {
        searchCache.put(key, new CacheEntry(value, System.nanoTime()));
        int overflow = searchCache.size() - config.getCacheMaxEntries();
        if (overflow <= 0) return;
        searchCache.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().storedAtNanos()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(searchCache::remove);
    }

    private record CacheEntry(Map<String, Object> value, long storedAtNanos) {
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
