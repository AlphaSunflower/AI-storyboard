package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepSeek 原生网页搜索的解析契约测试：结果只从结构化块里来，
 * 引用摘录按 URL 关联，重复 URL 去重，条数上限由服务端截断，缺结果块必须显式失败。
 */
class AgentScopeWebToolsSearchTest {

    @Test
    @SuppressWarnings("unchecked")
    void requestGoesToMessagesEndpointWithAnthropicHeaders() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        search.invoke(Map.of("query", "spring ai 2.0"), null);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString())
                .isEqualTo("https://api.deepseek.com/anthropic/v1/messages");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(request.headers().firstValue("x-api-key")).contains("sk-test");
        assertThat(request.headers().firstValue("authorization")).contains("Bearer sk-test");
    }

    /**
     * 请求体形状的静态不变量。运行期捕获 {@code BodyPublisher} 会与 JDK 的实现细节耦合
     * （发布者只能被订阅一次、泛型订阅者擦除），因此这里断言源码里的载荷骨架：
     * 任何一个字段被改坏都会让这条测试先红，而不是等线上搜索静默失效。
     */
    @Test
    void requestBodyKeepsModelNativeToolAndQueryInstruction() throws Exception {
        String source = Files.readString(
                Path.of("src", "main", "java", "com", "moon", "moonagent", "ai", "agent",
                        "agentscope", "AgentScopeWebTools.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("payload.put(\"model\", config.getModel())")
                .contains("payload.put(\"max_tokens\", config.getMaxTokens())")
                .contains("\"role\", \"user\"")
                .contains("\"type\", NATIVE_SEARCH_TOOL_TYPE")
                .contains("\"name\", WEB_SEARCH")
                .contains("\"max_uses\", config.getMaxUses()")
                .contains("Perform a web search for the query: ");
        assertThat(AgentScopeWebTools.NATIVE_SEARCH_TOOL_TYPE).isEqualTo("web_search_20250305");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsResultBlocksAndJoinsCitationSnippets() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "anything"));

        assertThat(result).containsEntry("ok", true).containsEntry("count", 1);
        assertThat(result.get("answer")).isEqualTo("A short summary.");
        assertThat(String.valueOf(result.get("results")))
                .contains("https://example.com/a")
                .contains("Alpha")
                .contains("quoted excerpt")
                .contains("2026-09-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncatesToRequestedLimitAndMarksTruncated() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, MULTI_BODY);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "many", "max_results", 2));

        assertThat(result).containsEntry("count", 2).containsEntry("truncated", true);
        assertThat(String.valueOf(result.get("results")))
                .contains("https://example.com/1")
                .contains("https://example.com/2")
                .doesNotContain("https://example.com/3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateUrlsAreDeduplicatedAcrossResultBlocks() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, DEDUPE_BODY);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "dupe"));

        assertThat(result).containsEntry("count", 1).containsEntry("truncated", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingResultBlockIsAnExplicitFailureNotProseScraping() throws Exception {
        HttpClient http = mock(HttpClient.class);
        // 模型只回了散文、上游没触发原生搜索：绝不能把正文当搜索结果。
        stub(http, 200, """
                {"content":[{"type":"text","text":"I could not search, but here is https://x.test"}]}
                """);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "no search"));

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("error")))
                .contains("no web_search_tool_result blocks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upstreamHttpErrorKeepsReadableProviderMessage() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 401, """
                {"error":{"type":"authentication_error","message":"invalid api key"}}
                """);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "auth"));

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("error"))).contains("invalid api key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonJsonHttpErrorStillReportsStatusAndBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 502, "<html>bad gateway</html>");
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "gateway"));

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("error")))
                .contains("502")
                .contains("bad gateway");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resultBlockErrorPayloadSurfacesInsteadOfEmptySuccess() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, """
                {"content":[{"type":"web_search_tool_result",
                 "content":{"type":"web_search_tool_result_error","error_code":"unavailable"}}]}
                """);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "err"));

        assertThat(result).containsEntry("ok", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankQueryFailsBeforeAnyRequest() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "   "));

        assertThat(result).containsEntry("ok", false);
        verify(http, times(0)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static final String BODY = """
            {"content":[
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://example.com/a","title":"Alpha",
                 "page_age":"2026-09-01"}]},
              {"type":"text","text":"A short summary.","citations":[
                {"type":"web_search_result_location","url":"https://example.com/a",
                 "cited_text":"quoted excerpt"}]}
            ]}
            """;

    private static final String MULTI_BODY = """
            {"content":[{"type":"web_search_tool_result","content":[
              {"type":"web_search_result","url":"https://example.com/1","title":"One"},
              {"type":"web_search_result","url":"https://example.com/2","title":"Two"},
              {"type":"web_search_result","url":"https://example.com/3","title":"Three"}]}]}
            """;

    private static final String DEDUPE_BODY = """
            {"content":[
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://example.com/same","title":"Same"}]},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://example.com/same","title":"Same again"}]}
            ]}
            """;

    @SuppressWarnings("unchecked")
    private static void stub(HttpClient http, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private static AgentScopeTool searchTool(HttpClient http) {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setApiKey("sk-test");
        return new AgentScopeWebTools(properties, http).tools().stream()
                .filter(tool -> AgentScopeWebTools.WEB_SEARCH.equals(tool.getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(AgentScopeTool tool, Map<String, Object> input) {
        return (Map<String, Object>) tool.invoke(input, null);
    }
}
