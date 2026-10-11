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
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;

/**
 * 网页搜索与抓取工具（自研，替换 Harness 自带的 Tavily {@code web_search}）。
 *
 * <p>{@code web_search} 调 Firecrawl REST {@code /v2/search}；密钥来自
 * {@code ai.agent.agentscope.web-search.firecrawl-api-key}。{@code web_fetch} 在服务端直接抓取，
 * 因此必须先过 {@link OutboundUrlGuard}，阻断 loopback / 私网 / 链路本地地址（SSRF）。
 *
 * <p>两个工具都通过 {@code AgentScopeToolPipeline} 注册，因此和自研工具一样进入工具审计。
 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeWebTools {

    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FETCH_CHARS = 20_000;
    private static final int HARD_FETCH_CHARS = 100_000;

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
                .build());
    }

    AgentScopeWebTools(AgentScopeProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public List<AgentScopeTool> tools() {
        AgentScopeProperties.WebSearch config = properties.getWebSearch();
        if (!config.isConfigured()) return List.of();
        return List.of(webSearch(config), webFetch(config));
    }

    private AgentScopeTool webSearch(AgentScopeProperties.WebSearch config) {
        return new AgentScopeTool(
                WEB_SEARCH,
                "Search the web for current information. Returns titles, URLs and snippets. "
                        + "Pass category='developer' to search public repositories, issues, pull "
                        + "requests and code documentation, or category='gov' for government sites.",
                AgentScopeTool.schema(Map.of(
                        "query", Map.of("type", "string"),
                        "max_results", Map.of("type", "integer",
                                "minimum", 1, "maximum", 10),
                        "category", Map.of("type", "string",
                                "enum", List.of("web", "developer", "gov"))),
                        List.of("query")),
                true, true, false,
                (input, param) -> search(
                        config,
                        string(input.get("query")),
                        integer(input.get("max_results"), config.getMaxResults()),
                        string(input.get("category"))));
    }

    private AgentScopeTool webFetch(AgentScopeProperties.WebSearch config) {
        return new AgentScopeTool(
                WEB_FETCH,
                "Fetch content from a public HTTP(S) URL and return a truncated text preview. "
                        + "Internal, loopback and private addresses are rejected.",
                AgentScopeTool.schema(Map.of(
                        "url", Map.of("type", "string"),
                        "max_chars", Map.of("type", "integer")),
                        List.of("url")),
                true, true, false,
                (input, param) -> fetch(config, string(input.get("url")),
                        integer(input.get("max_chars"), MAX_FETCH_CHARS)));
    }

    private Map<String, Object> search(AgentScopeProperties.WebSearch config,
                                       String query,
                                       int maxResults,
                                       String category) {
        if (query.isBlank()) {
            return Map.of("ok", false, "error", "query is required");
        }
        int limit = Math.max(1, Math.min(10, maxResults <= 0 ? config.getMaxResults() : maxResults));
        String key = cacheKey(query, limit, category);
        Map<String, Object> cached = cachedSearch(config, key);
        if (cached != null) {
            return cached;
        }
        // single-flight：同一 key 的并发搜索只打一次厂商接口，其余等待共享结果。
        CompletableFuture<Map<String, Object>> created = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return awaitShared(existing);
        }
        try {
            Map<String, Object> result = searchUncached(config, query, limit, category);
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

    private Map<String, Object> searchUncached(AgentScopeProperties.WebSearch config,
                                               String query,
                                               int maxResults,
                                               String category) {
        int limit = Math.max(1, Math.min(10, maxResults <= 0 ? config.getMaxResults() : maxResults));
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("query", query);
            payload.put("limit", limit);
            if (!category.isBlank() && !"web".equalsIgnoreCase(category)) {
                payload.put("categories", List.of(category));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getFirecrawlUrl()))
                    .timeout(config.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getFirecrawlApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of("ok", false,
                        "error", "Firecrawl search returned HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode web = root.path("data").path("web");
            List<Map<String, Object>> results = new java.util.ArrayList<>();
            if (web.isArray()) {
                for (JsonNode item : web) {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("title", item.path("title").asText(""));
                    entry.put("url", item.path("url").asText(""));
                    entry.put("snippet", trim(item.path("description").asText(""), 1200));
                    String itemCategory = item.path("category").asText("");
                    if (!itemCategory.isBlank()) entry.put("category", itemCategory);
                    results.add(entry);
                }
            }
            return Map.of("ok", true, "count", results.size(), "results", results);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "web_search failed: " + rootMessage(error));
        }
    }

    private Map<String, Object> fetch(AgentScopeProperties.WebSearch config,
                                      String url,
                                      int maxChars) {
        if (url.isBlank()) {
            return Map.of("ok", false, "error", "url is required");
        }
        try {
            OutboundUrlGuard.requireSafeHttpUrl(url);
        } catch (RuntimeException rejected) {
            return Map.of("ok", false,
                    "error", rootMessage(rejected));
        }
        int limit = Math.max(1, Math.min(HARD_FETCH_CHARS,
                maxChars <= 0 ? MAX_FETCH_CHARS : maxChars));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(config.getTimeout())
                    .header("User-Agent", "MoonAgent-WebFetch/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false,
                        "error", "web_fetch returned HTTP " + response.statusCode());
            }
            String body = response.body() == null ? "" : response.body();
            boolean truncated = body.length() > limit;
            return Map.of(
                    "ok", true,
                    "status", response.statusCode(),
                    "truncated", truncated,
                    "content", truncated ? body.substring(0, limit) + "\n...[truncated]" : body);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "web_fetch failed: " + rootMessage(error));
        }
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

    private static String cacheKey(String query, int limit, String category) {
        return (category == null ? "" : category.trim().toLowerCase(Locale.ROOT))
                + "|" + limit + "|" + query.trim().toLowerCase(Locale.ROOT);
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
