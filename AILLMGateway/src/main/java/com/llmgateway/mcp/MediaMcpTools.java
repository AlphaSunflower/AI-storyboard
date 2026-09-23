package com.llmgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import com.storyboard.common.MultipartBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Media tools exposed by the LLM gateway over MCP.
 *
 * MoonAgent injects projectId/conversationId/idempotencyKey at the top level,
 * so those fields are explicit tool parameters even though upstream providers do not use them.
 */
@Component
public class MediaMcpTools {

    private static final Logger log = LoggerFactory.getLogger(MediaMcpTools.class);
    private static final int MAX_SOURCE_IMAGE_BYTES = 50 * 1024 * 1024;

    private final GatewayRoutingService routingService;
    private final ImageEditService imageEditService;
    private final VideoGatewayService videoGatewayService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Autowired
    public MediaMcpTools(GatewayRoutingService routingService,
                         ImageEditService imageEditService,
                         VideoGatewayService videoGatewayService) {
        this(routingService, imageEditService, videoGatewayService, new ObjectMapper());
    }

    MediaMcpTools(GatewayRoutingService routingService,
                  ImageEditService imageEditService,
                  VideoGatewayService videoGatewayService,
                  ObjectMapper objectMapper) {
        this.routingService = routingService;
        this.imageEditService = imageEditService;
        this.videoGatewayService = videoGatewayService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Generate an image from text or edit one or more source images.")
    @McpTool(name = "generate_image", description = "Generate an image from text or edit source images.")
    public Map<String, Object> generateImage(
            @ToolParam(description = "TEXT_TO_IMAGE or IMAGE_EDIT")
            @McpToolParam(description = "TEXT_TO_IMAGE or IMAGE_EDIT", required = false) String mode,
            @ToolParam(description = "Image generation or edit prompt")
            @McpToolParam(description = "Image generation or edit prompt") String prompt,
            @ToolParam(description = "Source image URLs or data URIs")
            @McpToolParam(description = "Source image URLs or data URIs", required = false) List<String> sourceImages,
            @ToolParam(description = "Optional model; defaults to the gateway default image model")
            @McpToolParam(description = "Optional model", required = false) String model,
            @ToolParam(description = "Optional image size")
            @McpToolParam(description = "Optional image size", required = false) String size,
            @ToolParam(description = "Optional image quality")
            @McpToolParam(description = "Optional image quality", required = false) String quality,
            @ToolParam(description = "Number of images")
            @McpToolParam(description = "Number of images", required = false) Integer n,
            @ToolParam(description = "Optional negative prompt")
            @McpToolParam(description = "Optional negative prompt", required = false) String negativePrompt,
            @ToolParam(description = "Project ID injected by MoonAgent")
            @McpToolParam(description = "Project ID", required = false) String projectId,
            @ToolParam(description = "Conversation ID injected by MoonAgent")
            @McpToolParam(description = "Conversation ID", required = false) String conversationId,
            @ToolParam(description = "Idempotency key injected by MoonAgent")
            @McpToolParam(description = "Idempotency key", required = false) String idempotencyKey) {
        try {
            List<String> sources = sourceImages == null ? List.of() : sourceImages.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            String effectiveModel = resolveModel(model, "image");
            if (sources.isEmpty()) {
                ObjectNode request = objectMapper.createObjectNode();
                request.put("model", effectiveModel);
                request.put("prompt", prompt == null ? "" : prompt);
                if (size != null && !size.isBlank()) request.put("size", size);
                if (quality != null && !quality.isBlank()) request.put("quality", quality);
                request.put("n", n == null || n < 1 ? 1 : n);
                if (negativePrompt != null && !negativePrompt.isBlank()) request.put("negative_prompt", negativePrompt);
                return imageResult(routingService.route("/images/generations", request.toString()), "image generation");
            }

            MultipartBuilder multipart = new MultipartBuilder()
                    .field("model", effectiveModel)
                    .field("prompt", prompt == null ? "" : prompt)
                    .field("n", String.valueOf(n == null || n < 1 ? 1 : n));
            if (size != null && !size.isBlank()) multipart.field("size", size);
            if (quality != null && !quality.isBlank()) multipart.field("quality", quality);
            if (negativePrompt != null && !negativePrompt.isBlank()) multipart.field("negative_prompt", negativePrompt);
            for (int i = 0; i < sources.size(); i++) {
                SourceImage source = loadSourceImage(sources.get(i));
                multipart.file("image", "source-" + (i + 1) + "." + extension(source.contentType()),
                        source.contentType(), source.bytes());
            }
            return imageResult(imageEditService.edit(multipart.build(), "application/octet-stream"), "image edit");
        } catch (Exception e) {
            log.warn("generate_image failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage() == null ? "image generation failed" : e.getMessage());
        }
    }

    @Tool(description = "Create a video generation task from text and optional reference media.")
    @McpTool(name = "generate_video", description = "Create a video generation task from text and optional reference media.")
    public Map<String, Object> generateVideo(
            @ToolParam(description = "Video prompt")
            @McpToolParam(description = "Video prompt") String prompt,
            @ToolParam(description = "Reference images with url and role")
            @McpToolParam(description = "Reference images", required = false) List<Map<String, Object>> referenceImages,
            @ToolParam(description = "Optional model; defaults to the gateway default video model")
            @McpToolParam(description = "Optional model", required = false) String model,
            @ToolParam(description = "Duration in seconds")
            @McpToolParam(description = "Duration in seconds", required = false) Integer duration,
            @ToolParam(description = "Aspect ratio such as 16:9")
            @McpToolParam(description = "Aspect ratio", required = false) String aspectRatio,
            @ToolParam(description = "Resolution")
            @McpToolParam(description = "Resolution", required = false) String resolution,
            @ToolParam(description = "Optional size")
            @McpToolParam(description = "Optional size", required = false) String size,
            @ToolParam(description = "Optional negative prompt")
            @McpToolParam(description = "Optional negative prompt", required = false) String negativePrompt,
            @ToolParam(description = "Optional seed")
            @McpToolParam(description = "Optional seed", required = false) Integer seed,
            @ToolParam(description = "Optional first-frame image URL")
            @McpToolParam(description = "Optional first-frame image URL", required = false) String imageUrl,
            @ToolParam(description = "Optional reference video URLs")
            @McpToolParam(description = "Optional reference video URLs", required = false) List<String> referenceVideos,
            @ToolParam(description = "Optional reference audio URLs")
            @McpToolParam(description = "Optional reference audio URLs", required = false) List<String> referenceAudios,
            @ToolParam(description = "Project ID injected by MoonAgent")
            @McpToolParam(description = "Project ID", required = false) String projectId,
            @ToolParam(description = "Conversation ID injected by MoonAgent")
            @McpToolParam(description = "Conversation ID", required = false) String conversationId,
            @ToolParam(description = "Idempotency key injected by MoonAgent")
            @McpToolParam(description = "Idempotency key", required = false) String idempotencyKey) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", resolveModel(model, "video"));
            request.put("prompt", prompt == null ? "" : prompt);
            if (duration != null && duration > 0) request.put("duration", duration);
            putText(request, "aspectRatio", aspectRatio);
            putText(request, "resolution", resolution);
            putText(request, "size", size);
            putText(request, "negativePrompt", negativePrompt);
            if (seed != null) request.put("seed", seed);
            putText(request, "imageUrl", imageUrl);
            if (referenceImages != null && !referenceImages.isEmpty()) {
                request.set("referenceImages", objectMapper.valueToTree(referenceImages));
            }
            putStringArray(request, "referenceVideos", referenceVideos);
            putStringArray(request, "referenceAudios", referenceAudios);

            VideoResult result = videoGatewayService.create(request.toString());
            JsonNode root = readJson(result.body());
            if (result.status() < 200 || result.status() >= 300) {
                return Map.of("error", extractError(root, result.body(), "video creation failed"));
            }
            if (hasError(root)) {
                return Map.of("error", extractError(root, result.body(), "video creation failed"));
            }
            Map<String, Object> output = new LinkedHashMap<>();
            copyText(root, output, "taskId");
            copyText(root, output, "status");
            copyText(root, output, "url");
            copyText(root, output, "progress");
            copyText(root, output, "vendor");
            if (!output.containsKey("status")) output.put("status", "queued");
            return output;
        } catch (Exception e) {
            log.warn("generate_video failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage() == null ? "video creation failed" : e.getMessage());
        }
    }

    private Map<String, Object> imageResult(RouteResult result, String operation) throws IOException {
        JsonNode root = readJson(result.body());
        if (result.status() < 200 || result.status() >= 300) {
            return Map.of("error", extractError(root, result.body(), operation + " failed"));
        }
        List<String> urls = new ArrayList<>();
        collectImageUrls(root, urls);
        if (urls.isEmpty()) {
            return Map.of("error", operation + " returned no image URLs");
        }
        return Map.of("resultUrls", urls.stream().distinct().toList());
    }

    private void collectImageUrls(JsonNode node, List<String> urls) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            addText(node.path("url"), urls);
            addText(node.path("b64_json"), urls);
            for (String field : List.of("data", "images", "output", "results")) {
                collectImageUrls(node.path(field), urls);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) collectImageUrls(item, urls);
        }
    }

    private static void addText(JsonNode node, List<String> urls) {
        if (node != null && node.isTextual() && !node.asText().isBlank()) urls.add(node.asText());
    }

    private String resolveModel(String requested, String type) throws IOException {
        if (requested != null && !requested.isBlank()) return requested.trim();
        JsonNode root = readJson(routingService.fetchModels(type));
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("no default " + type + " model configured");
        }
        for (JsonNode model : data) {
            if (model.path("is_default").asBoolean(false)
                    || model.path("isDefault").asBoolean(false)
                    || model.path("default").asBoolean(false)) {
                return model.path("id").asText("");
            }
        }
        String first = data.get(0).path("id").asText("");
        if (first.isBlank()) throw new IllegalStateException("default " + type + " model has no id");
        return first;
    }

    private SourceImage loadSourceImage(String source) throws Exception {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source image is blank");
        String value = source.trim();
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("invalid source image data URI");
            String header = value.substring(5, comma);
            String contentType = header.split(";", 2)[0].trim();
            if (contentType.isBlank()) contentType = "image/png";
            byte[] bytes = header.toLowerCase(Locale.ROOT).contains(";base64")
                    ? Base64.getDecoder().decode(value.substring(comma + 1))
                    : value.substring(comma + 1).getBytes(StandardCharsets.UTF_8);
            return new SourceImage(contentType, requireImageSize(bytes));
        }
        if (value.startsWith("file:")) {
            Path path = Path.of(URI.create(value));
            return new SourceImage(contentTypeFor(path.toString()), requireImageSize(Files.readAllBytes(path)));
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(value))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("failed to download source image: HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
            int semicolon = contentType.indexOf(';');
            if (semicolon >= 0) contentType = contentType.substring(0, semicolon);
            return new SourceImage(contentType, requireImageSize(response.body()));
        }
        Path path = Path.of(value);
        return new SourceImage(contentTypeFor(value), requireImageSize(Files.readAllBytes(path)));
    }

    private static byte[] requireImageSize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("source image is empty");
        if (bytes.length > MAX_SOURCE_IMAGE_BYTES) throw new IOException("source image exceeds 50MB");
        return bytes;
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private static String extension(String contentType) {
        if ("image/jpeg".equalsIgnoreCase(contentType)) return "jpg";
        if ("image/webp".equalsIgnoreCase(contentType)) return "webp";
        if ("image/gif".equalsIgnoreCase(contentType)) return "gif";
        return "png";
    }

    private JsonNode readJson(String body) throws IOException {
        if (body == null || body.isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(body);
    }

    private static boolean hasError(JsonNode root) {
        JsonNode error = root.path("error");
        if (error.isTextual()) return !error.asText().isBlank();
        if (error.isObject()) {
            return !error.path("message").asText("").isBlank() || !error.path("code").asText("").isBlank();
        }
        return !error.isMissingNode() && !error.isNull();
    }
    private static String extractError(JsonNode root, String rawBody, String fallback) {
        JsonNode error = root.path("error");
        if (error.isTextual() && !error.asText().isBlank()) return error.asText();
        String message = error.path("message").asText("");
        if (message.isBlank()) message = root.path("message").asText("");
        if (message.isBlank()) message = rawBody == null ? "" : rawBody.trim();
        return message.isBlank() ? fallback : message;
    }

    private static void putText(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.put(key, value);
    }

    private static void putStringArray(ObjectNode node, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        ArrayNode array = node.putArray(key);
        values.stream().filter(value -> value != null && !value.isBlank()).forEach(array::add);
    }

    private static void copyText(JsonNode root, Map<String, Object> output, String key) {
        JsonNode value = root.path(key);
        if (value.isTextual() && !value.asText().isBlank()) output.put(key, value.asText());
    }

    private record SourceImage(String contentType, byte[] bytes) {}
}

