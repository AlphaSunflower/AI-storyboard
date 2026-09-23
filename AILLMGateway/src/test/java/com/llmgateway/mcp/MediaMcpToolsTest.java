package com.llmgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaMcpToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayRoutingService routingService = mock(GatewayRoutingService.class);
    private final ImageEditService imageEditService = mock(ImageEditService.class);
    private final VideoGatewayService videoGatewayService = mock(VideoGatewayService.class);
    private final MediaMcpTools tools = new MediaMcpTools(
            routingService, imageEditService, videoGatewayService, objectMapper);

    @Test
    void textToImageUsesDefaultModelAndNormalizesUrls() throws Exception {
        when(routingService.fetchModels("image")).thenReturn("""
                {"object":"list","data":[{"id":"default-image","type":"image","is_default":true}]}
                """);
        when(routingService.route(eq("/images/generations"), anyString()))
                .thenReturn(new RouteResult(200, "{\"data\":[{\"url\":\"https://img/1.png\"}]}"));

        Map<String, Object> result = tools.generateImage(
                "TEXT_TO_IMAGE", "画一座未来城市", null, "", "", "", null, "",
                "project-1", "conversation-1", "key-1");

        assertThat(result).containsEntry("resultUrls", List.of("https://img/1.png"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(routingService).route(eq("/images/generations"), body.capture());
        JsonNode request = objectMapper.readTree(body.getValue());
        assertThat(request.path("model").asText()).isEqualTo("default-image");
        assertThat(request.path("prompt").asText()).isEqualTo("画一座未来城市");
    }

    @Test
    void imageEditBuildsMultipartWithModelPromptAndImage() throws Exception {
        when(routingService.fetchModels("image")).thenReturn("""
                {"data":[{"id":"edit-image","is_default":true}]}
                """);
        when(imageEditService.edit(org.mockito.ArgumentMatchers.any(byte[].class), anyString()))
                .thenReturn(new RouteResult(200, "{\"data\":[{\"url\":\"https://img/edit.png\"}]}"));

        Map<String, Object> result = tools.generateImage(
                "IMAGE_EDIT", "改成蓝色", List.of("data:image/png;base64,iVBORw0KGgo="),
                "", "", "", 1, "", "project-1", "conversation-1", "key-1");

        assertThat(result).containsEntry("resultUrls", List.of("https://img/edit.png"));
        ArgumentCaptor<byte[]> multipart = ArgumentCaptor.forClass(byte[].class);
        verify(imageEditService).edit(multipart.capture(), eq("application/octet-stream"));
        String content = new String(multipart.getValue(), StandardCharsets.ISO_8859_1);
        String encodedPrompt = new String("改成蓝色".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertThat(content).contains("name=\"model\"").contains("edit-image")
                .contains("name=\"prompt\"").contains(encodedPrompt)
                .contains("name=\"image\"").contains("source-1.png");
    }

    @Test
    void videoCreatesNormalizedQueuedTask() throws Exception {
        when(routingService.fetchModels("video")).thenReturn("""
                {"data":[{"id":"default-video","is_default":true}]}
                """);
        when(videoGatewayService.create(anyString()))
                .thenReturn(new VideoResult(200, "{\"taskId\":\"task-1\",\"status\":\"queued\",\"url\":\"\"}"));

        Map<String, Object> result = tools.generateVideo(
                "生成产品短片", List.of(Map.of("url", "https://img/ref.png", "role", "FIRST_FRAME")),
                "", 8, "16:9", "1080p", "", "", null, "", null, null,
                "project-1", "conversation-1", "key-1");

        assertThat(result)
                .containsEntry("taskId", "task-1")
                .containsEntry("status", "queued");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(videoGatewayService).create(body.capture());
        JsonNode request = objectMapper.readTree(body.getValue());
        assertThat(request.path("model").asText()).isEqualTo("default-video");
        assertThat(request.path("prompt").asText()).isEqualTo("生成产品短片");
        assertThat(request.path("referenceImages").get(0).path("role").asText()).isEqualTo("FIRST_FRAME");
    }

    @Test
    void exposesExactMoonAgentToolNames() throws Exception {
        assertThat(MediaMcpTools.class.getMethod("generateImage",
                String.class, String.class, List.class, String.class, String.class, String.class,
                Integer.class, String.class, String.class, String.class, String.class
        ).getAnnotation(org.springframework.ai.mcp.annotation.McpTool.class).name())
                .isEqualTo("generate_image");
        assertThat(MediaMcpTools.class.getMethod("generateVideo",
                String.class, List.class, String.class, Integer.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, List.class, List.class,
                String.class, String.class, String.class
        ).getAnnotation(org.springframework.ai.mcp.annotation.McpTool.class).name())
                .isEqualTo("generate_video");
    }
}


