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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeWebToolsCacheTest {

    private static final String BODY = """
            {"success":true,"data":{"web":[
              {"url":"https://example.com/a","title":"A","description":"first"}
            ]}}
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

    private static AgentScopeTool searchTool(HttpClient http) {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setFirecrawlApiKey("fc-test");
        return new AgentScopeWebTools(properties, http).tools().stream()
                .filter(tool -> AgentScopeWebTools.WEB_SEARCH.equals(tool.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static HttpResponse<String> response(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
