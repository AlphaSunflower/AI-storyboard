package com.storyboard.asset.service.impl;

import com.storyboard.asset.config.InternalApiProperties;
import com.storyboard.common.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectAccessServiceImplTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsProjectOwnedByAnotherUser() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/projects/p1/ownership", exchange -> respond(exchange, """
                {"code":200,"message":"ok","data":{"projectId":"p1","userId":"owner","exists":true}}
                """));
        server.start();
        ProjectAccessServiceImpl service = service();

        assertThatThrownBy(() -> service.requireOwned("attacker", "p1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目不存在或无权访问");
    }

    @Test
    void allowsProjectOwner() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/projects/p1/ownership", exchange -> respond(exchange, """
                {"code":200,"message":"ok","data":{"projectId":"p1","userId":"owner","exists":true}}
                """));
        server.start();
        ProjectAccessServiceImpl service = service();

        assertThat(service.requireOwned("owner", "p1").userId()).isEqualTo("owner");
    }

    private ProjectAccessServiceImpl service() {
        return new ProjectAccessServiceImpl(new InternalApiProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret"));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
