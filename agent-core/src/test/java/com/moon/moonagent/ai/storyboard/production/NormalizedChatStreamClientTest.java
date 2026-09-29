package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.AiConfigProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedChatStreamClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsReasoningAndContentDeltasFromGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions/stream-events", exchange -> {
            byte[] body = """
                    {"seq":1,"type":"reasoning_delta","delta":"先分析"}
                    {"seq":2,"type":"content_delta","delta":"正式输出"}
                    {"seq":3,"type":"done","finishReason":"stop"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AiConfigProperties.Gateway gateway = new AiConfigProperties.Gateway();
        gateway.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        gateway.setApiKey("test-key");
        NormalizedChatStreamClient client =
                new NormalizedChatStreamClient(new AiConfigProperties(gateway));
        List<String> reasoning = new ArrayList<>();
        List<String> content = new ArrayList<>();

        NormalizedChatStreamClient.StreamResult result = client.stream(
                "test-model", "system", "user", 30,
                content::add, reasoning::add);

        assertThat(result.completed()).isTrue();
        assertThat(result.content()).isEqualTo("正式输出");
        assertThat(result.reasoning()).isEqualTo("先分析");
        assertThat(content).containsExactly("正式输出");
        assertThat(reasoning).containsExactly("先分析");
    }
}
