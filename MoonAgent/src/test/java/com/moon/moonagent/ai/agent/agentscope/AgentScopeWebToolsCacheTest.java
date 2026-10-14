package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeWebToolsCacheTest {

    /** 一次 native 搜索的最小结构化响应：结果块 + 带引用摘录的 text 块。 */
    private static final String BODY = """
            {"content":[
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://example.com/a","title":"A",
                 "page_age":"2026-09-01"}]},
              {"type":"text","text":"summary","citations":[
                {"type":"web_search_result_location","url":"https://example.com/a",
                 "cited_text":"first"}]}
            ]}
            """;

    @Test
    @SuppressWarnings("unchecked")
    void repeatedSearchHitsTheCache() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = response(200, BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);
        AgentScopeTool search = searchTool(http);

        Object first = search.invoke(Map.of("query", "agent scope"), null);
        Object second = search.invoke(Map.of("query", "Agent Scope"), null);

        assertThat(first.toString()).contains("example.com/a");
        assertThat(second.toString()).contains("example.com/a");
        verify(http, times(1))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void differentQueryOrLimitIsNotShared() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = response(200, BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);
        AgentScopeTool search = searchTool(http);

        search.invoke(Map.of("query", "alpha"), null);
        search.invoke(Map.of("query", "beta"), null);
        search.invoke(Map.of("query", "alpha", "max_results", 3), null);

        verify(http, times(3))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failuresAreNotCached() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> failure = response(500, "{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure);
        AgentScopeTool search = searchTool(http);

        Object first = search.invoke(Map.of("query", "alpha"), null);
        Object second = search.invoke(Map.of("query", "alpha"), null);

        assertThat(first.toString()).contains("ok=false");
        assertThat(second.toString()).contains("ok=false");
        verify(http, times(2))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentIdenticalSearchesShareOneCall() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    release.await(10, TimeUnit.SECONDS);
                    return response(200, BODY);
                });
        AgentScopeTool search = searchTool(http);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(
                    () -> search.invoke(Map.of("query", "shared"), null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (calls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Future<Object> second = pool.submit(
                    () -> search.invoke(Map.of("query", "shared"), null));
            Thread.sleep(200);
            assertThat(calls.get()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).toString()).contains("example.com/a");
            assertThat(second.get(10, TimeUnit.SECONDS).toString()).contains("example.com/a");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetchRefusesRedirectInsteadOfFollowingIt() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> redirect = byteResponse(302, new byte[0]);
        // 302 无 Location 也要拒绝：跟随跳转会绕过 SSRF 守卫（首跳公网、二跳私网）。
        doReturn(redirect).when(http).<byte[]>send(any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        AgentScopeTool fetch = tool(http, AgentScopeWebTools.WEB_FETCH);

        Object result = fetch.invoke(Map.of("url", "https://example.com/redirected"), null);

        assertThat(result.toString()).contains("refused to follow HTTP 302");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetchRejectsPrivateAddressesBeforeAnyRequest() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AgentScopeTool fetch = tool(http, AgentScopeWebTools.WEB_FETCH);

        Object result = fetch.invoke(Map.of("url", "http://169.254.169.254/latest/meta-data"), null);

        assertThat(result.toString()).contains("ok=false");
        verify(http, times(0))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetchConvertsHtmlToMarkdown() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String html = "<html><head><title>Title</title><style>b{}</style></head><body>"
                + "<h1>Heading</h1><p>Body <a href=\"https://example.com/x\">link</a></p>"
                + "<script>var x = 1;</script></body></html>";
        HttpResponse<byte[]> ok = byteResponse(200, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(ok.headers()).thenReturn(headers("text/html; charset=utf-8"));
        doReturn(ok).when(http).<byte[]>send(any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        AgentScopeTool fetch = tool(http, AgentScopeWebTools.WEB_FETCH);

        Object result = fetch.invoke(Map.of("url", "https://example.com/page"), null);

        assertThat(result.toString())
                .contains("html->markdown")
                .contains("# Heading")
                .contains("https://example.com/x")
                .doesNotContain("var x = 1");
    }

    private static AgentScopeTool searchTool(HttpClient http) {
        return tool(http, AgentScopeWebTools.WEB_SEARCH);
    }

    private static AgentScopeTool tool(HttpClient http, String name) {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");
        properties.getWebSearch().setApiKey("sk-test");
        return new AgentScopeWebTools(properties, http).tools().stream()
                .filter(tool -> name.equals(tool.getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> byteResponse(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    private static java.net.http.HttpHeaders headers(String contentType) {
        return java.net.http.HttpHeaders.of(
                Map.of("content-type", java.util.List.of(contentType)), (a, b) -> true);
    }
}
