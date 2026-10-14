package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自托管 SearXNG 后端的契约测试：请求打自己的实例、无需任何第三方 key、
 * 摘要取 {@code content}、403 要翻译成「去开 json 输出」的可执行提示。
 *
 * <p>JSON 形状依据 SearXNG 官方文档：{@code /search?format=json}
 * （https://docs.searxng.org/dev/search_api.html ），且 {@code search.formats}
 * 默认只有 {@code html}，必须显式加 {@code json}
 * （https://docs.searxng.org/admin/settings/settings_search.html ）。
 */
class AgentScopeWebToolsSearxngTest {

    private static final String BODY = """
            {"query":"searxng","number_of_results":3,"results":[
              {"url":"https://example.com/a","title":"Alpha","content":"first snippet",
               "publishedDate":"2026-09-01T00:00:00","engines":["duckduckgo","brave"]},
              {"url":"https://example.com/b","title":"Beta","content":"second snippet"},
              {"url":"https://example.com/a","title":"Alpha duplicate","content":"dup"}
            ],"suggestions":["searxng docker"],"answers":[]}
            """;

    @Test
    @SuppressWarnings("unchecked")
    void requestGoesToOwnInstanceWithJsonFormatAndNoApiKey() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        search.invoke(Map.of("query", "searxng"), null);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).isEqualTo("http://localhost:8888/search");
        assertThat(request.method()).isEqualTo("POST");
        // 自托管的关键性质：没有任何凭据头。
        assertThat(request.headers().firstValue("authorization")).isEmpty();
        assertThat(request.headers().firstValue("x-api-key")).isEmpty();
        assertThat(request.headers().firstValue("content-type"))
                .contains("application/x-www-form-urlencoded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsResultsDedupesUrlsAndCarriesSuggestions() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "searxng"));

        assertThat(result).containsEntry("ok", true).containsEntry("provider", "searxng");
        // 三条里 a 重复一次 → 去重后 2 条。
        assertThat(result).containsEntry("count", 2);
        assertThat(String.valueOf(result.get("results")))
                .contains("https://example.com/a")
                .contains("first snippet")
                .contains("https://example.com/b")
                .contains("2026-09-01T00:00:00")
                .doesNotContain("Alpha duplicate");
        assertThat(String.valueOf(result.get("suggestions"))).contains("searxng docker");
    }

    @Test
    @SuppressWarnings("unchecked")
    void respectsMaxResults() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "searxng", "max_results", 1));

        assertThat(result).containsEntry("count", 1).containsEntry("truncated", true);
        assertThat(String.valueOf(result.get("results"))).doesNotContain("example.com/b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void http403ExplainsHowToEnableJsonOutput() throws Exception {
        HttpClient http = mock(HttpClient.class);
        // SearXNG 实例没开 json 输出时的真实行为就是 403。
        stub(http, 403, "Forbidden");
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "anything"));

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("error")))
                .contains("format=json")
                .contains("formats");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyResultsIsSuccessWithRefineHintNotAFailure() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, "{\"results\":[]}");
        AgentScopeTool search = searchTool(http);

        Map<String, Object> result = invoke(search, Map.of("query", "zzzz"));

        assertThat(result).containsEntry("ok", true).containsEntry("count", 0);
        assertThat(String.valueOf(result.get("note"))).contains("Refine the query");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noApiKeyIsNeededToRegisterTools() {
        AgentScopeProperties properties = new AgentScopeProperties();
        // 默认后端就是 searxng，且不需要任何 key。
        assertThat(properties.getWebSearch().isConfigured()).isTrue();
        assertThat(new AgentScopeWebTools(properties).tools()).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deepSeekProviderWithoutKeyStaysUnregistered() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");

        assertThat(properties.getWebSearch().isConfigured()).isFalse();
        assertThat(new AgentScopeWebTools(properties).tools()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownProviderFallsBackToSearxngInsteadOfDisablingSearch() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("tavily");

        assertThat(properties.getWebSearch().getProvider())
                .isEqualTo(AgentScopeProperties.WebSearch.Provider.SEARXNG);
        assertThat(properties.getWebSearch().isConfigured()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchesAreDeduplicatedAcrossRequestsByCache() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, BODY);
        AgentScopeTool search = searchTool(http);

        invoke(search, Map.of("query", "cached"));
        invoke(search, Map.of("query", "CACHED"));

        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

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
        properties.getWebSearch().setProvider("searxng");
        properties.getWebSearch().setSearxngUrl("http://localhost:8888");
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
