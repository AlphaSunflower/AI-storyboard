package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import com.moon.moonagent.service.FileStorageService;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoPollExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void completedTaskPersistsVideoAndUpdatesOwnerPorts() throws IOException {
        startServer("""
                {"taskId":"task-1","status":"succeeded"}
                """);
        AiConfigProperties config = config();
        SceneGenerationBridge sceneBridge = mock(SceneGenerationBridge.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);
        SceneView scene = new SceneView();
        scene.id = "scene-1";
        when(sceneBridge.getSceneByVideoTaskId("task-1")).thenReturn(scene);
        when(fileStorageService.saveVideoBytes(
                argThat(bytes -> bytes.length == 3), eq("mp4")))
                .thenReturn("/api/files/videos/v.mp4?token=1.sig");

        Map<String, String> result = new VideoPollExecutor(
                config, sceneBridge, fileStorageService, writer).pollVideoTask("task-1");

        assertEquals("completed", result.get("status"));
        assertEquals("/api/files/videos/v.mp4?token=1.sig", result.get("videoUrl"));
        verify(sceneBridge).updateScene(argThat(view ->
                "completed".equals(view.videoStatus)
                        && "/api/files/videos/v.mp4?token=1.sig".equals(view.videoUrl)));
        verify(writer).updateByTaskId(eq("task-1"), argThat(update ->
                "completed".equals(update.status())
                        && update.resultUrls().contains("/api/files/videos/v.mp4?token=1.sig")));
    }

    @Test
    void failedTaskUpdatesRecordAndPreservesError() throws IOException {
        startServer("""
                {"taskId":"task-2","status":"failed","error":"vendor rejected"}
                """);
        AiConfigProperties config = config();
        SceneGenerationBridge sceneBridge = mock(SceneGenerationBridge.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);

        Map<String, String> result = new VideoPollExecutor(
                config, sceneBridge, fileStorageService, writer).pollVideoTask("task-2");

        assertEquals("failed", result.get("status"));
        assertEquals("vendor rejected", result.get("error"));
        verify(writer).updateByTaskId(eq("task-2"), argThat(update ->
                "failed".equals(update.status()) && "vendor rejected".equals(update.error())));
    }

    private AiConfigProperties config() {
        AiConfigProperties config = new AiConfigProperties(new AiConfigProperties.Gateway());
        config.getGateway().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        return config;
    }

    private void startServer(String statusJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/videos/", exchange -> {
            byte[] body;
            if (exchange.getRequestURI().getPath().endsWith("/content")) {
                body = new byte[]{1, 2, 3};
            } else {
                body = statusJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
