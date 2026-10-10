package com.moon.moonagent.ai.agent.sandbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MoonSandboxClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsInternalSecretAndParsesExecutionResult() throws Exception {
        AtomicReference<String> secret = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sandbox/c1/execute", exchange -> {
            secret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"exitCode":0,"stdout":"ok","stderr":"","stdoutTruncated":false,
                     "stderrTruncated":false,"timedOut":false,"durationMs":12,
                     "files":[{"path":"output/a.txt","sizeBytes":2,"mimeType":"text/plain"}]}
                    """);
        });
        server.createContext("/internal/sandbox/c1/files", exchange -> {
            secret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            respond(exchange, 200, "[]");
        });
        server.start();
        MoonSandboxClient client = new MoonSandboxClient(properties(server));

        var result = client.execute("c1", "print('ok')", List.of("a"), 30);

        assertThat(secret.get()).isEqualTo("secret");
        assertThat(body.get()).contains("print('ok')").contains("\"a\"");
        assertThat(result.exitCode()).isZero();
        assertThat(result.files()).extracting(SandboxFileView::path)
                .containsExactly("output/a.txt");
        client.list("c1", "output");
        assertThat(secret.get()).isEqualTo("secret");
    }

    private MoonSandboxClientProperties properties(HttpServer server) {
        MoonSandboxClientProperties properties = new MoonSandboxClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalSecret("secret");
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
