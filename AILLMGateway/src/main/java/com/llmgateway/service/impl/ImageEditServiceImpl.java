package com.llmgateway.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.ChannelKeyService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.RouteResolver;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.storyboard.common.image.SeedreamImageProtocol;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * 图改图（edits）网关服务实现：接收 OpenAI 原生 multipart 字节流，
 * 从字节流解析 model 字段 → 复用 model_route 渠道路由 → 原样透传上游。
 *
 * 行为与 GatewayRoutingService 完全一致：
 *   429/5xx → 切下一渠道；4xx → 透传错误体；全渠道失败 → 50301；每次调用落 call_log
 */
@Service
@RequiredArgsConstructor
public class ImageEditServiceImpl implements ImageEditService {

    private static final Logger log = LoggerFactory.getLogger(ImageEditServiceImpl.class);

    /** 上游 edits 端点路径（openai_compatible 渠道统一使用；baseUrl 已含 /v1 前缀，故不带） */
    private static final String EDIT_PATH = "/images/edits";
    private static final String DOUBAO_MODEL_PREFIX = "doubao-seedream-";

    private final UpstreamClient upstreamClient;
    private final CallLogService callLogService;
    private final RouteResolver routeResolver;
    private final ChannelKeyService channelKeyService;

    @Value("${gateway.vendor.doubao.image-edit-path:/images/generations}")
    private String doubaoImageEditPath;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public RouteResult edit(byte[] multipartBody, String contentType) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        try {
            // 0. 从 multipart 字节流提取 boundary，重建上游需要的 multipart Content-Type
            //    （实测 2026-08-08：@RequestBody byte[] 收 multipart 会被 Spring 解析器消费 body → 500，
            //    调用方改发 octet-stream 后 body 可达，转发头须在此重建）
            String upstreamContentType = buildMultipartContentType(multipartBody);

            // 1. 从 multipart 字节流轻量解析 model 字段（name="model" part 的 body）
            model = parseModelField(multipartBody);
            if (model == null || model.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 2. 按统一路由规则取候选（启停/优先级/权重与 chat/image 一致）
            List<RouteResolver.RouteCandidate> candidates = routeResolver.resolve(model, null);
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 3. 逐个渠道尝试（失败切下一个）；multipart 体不做上游模型/参数改写（见文档限制）
            int attempt = 0;
            for (RouteResolver.RouteCandidate candidate : candidates) {
                Channel channel = candidate.channel();
                attempt++;
                try {
                    channelId = channel.getId();
                    ChannelKeyService.KeySelection key = channelKeyService.select(channel);
                    RouteResult resp = isDoubaoModel(model)
                            ? editDoubao(channel, key.plainKey(), model, multipartBody)
                            : editOpenAiCompatible(channel, key.plainKey(), upstreamContentType, multipartBody);
                    int status = resp.status();
                    if (status >= 400) {
                        String error = upstreamClient.extractError(resp.body());
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；其余 4xx 业务错误直接透传（带上游真实状态码，避免被 200 包装致 Backend 误判成功）
                        if (status != 429 && status < 500) {
                            callLogService.log(model, channelId, "error",
                                    System.currentTimeMillis() - start, error, null, null, null,
                                    model, attempt, null, null);
                            return resp;
                        }
                        if (status == 429 || status == 401 || status == 403 || status >= 500) {
                            channelKeyService.markFailure(key.keyId(), status);
                        }
                        continue;
                    }
                    channelKeyService.markSuccess(key.keyId());
                    callLogService.log(model, channelId, "success",
                            System.currentTimeMillis() - start, null, null, null, null,
                            model, attempt, null, null);
                    return resp;
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    log.warn("渠道 {} 调用异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(50301, "all channels failed for model: " + model);
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, be.getMessage(), null, null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, e.getMessage(), null, null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    private RouteResult editOpenAiCompatible(Channel channel, String apiKey,
                                             String contentType, byte[] multipartBody) {
        HttpResponse<String> response = upstreamClient.postMultipart(
                channel.getBaseUrl(), EDIT_PATH, apiKey, contentType, multipartBody);
        return new RouteResult(response.statusCode(), response.body());
    }

    private RouteResult editDoubao(Channel channel, String apiKey, String model,
                                   byte[] multipartBody) throws Exception {
        String prompt = partText(multipartBody, "prompt");
        String size = partText(multipartBody, "size");
        String countValue = partText(multipartBody, "n");
        Integer count = null;
        if (countValue != null && !countValue.isBlank()) {
            try {
                count = Integer.parseInt(countValue.trim());
            } catch (NumberFormatException ignored) {
                count = null;
            }
        }
        byte[] imageBytes = partBytes(multipartBody, "image");
        byte[] maskBytes = partBytes(multipartBody, "mask");
        if (prompt == null || prompt.isBlank()) {
            return new RouteResult(400, "{\"error\":{\"message\":\"prompt 不能为空\"}}");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return new RouteResult(400, "{\"error\":{\"message\":\"image 不能为空\"}}");
        }

        String mimeType = partContentType(multipartBody, "image");
        if (mimeType == null || !mimeType.startsWith("image/")) mimeType = detectImageMime(imageBytes);
        String imageDataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        List<String> referenceImages = new ArrayList<>();
        referenceImages.add(imageDataUri);
        String effectivePrompt = prompt;
        if (maskBytes != null && maskBytes.length > 0) {
            byte[] guideImage = buildMaskGuideImage(imageBytes, maskBytes);
            if (guideImage != null && guideImage.length > 0) {
                referenceImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(guideImage));
                effectivePrompt = "第一张图是原图，第二张图中的洋红色区域是允许修改的选区；"
                        + "只能修改洋红色区域，其他区域必须保持原图不变。\n" + prompt;
            }
        }
        String requestJson = SeedreamImageProtocol.buildRequest(
                model, effectivePrompt, referenceImages, size, count).toString();
        String editPath = doubaoImageEditPath == null || doubaoImageEditPath.isBlank()
                ? "/images/generations"
                : doubaoImageEditPath.trim();
        HttpResponse<InputStream> response = upstreamClient.postJsonStream(
                channel.getBaseUrl(), editPath, apiKey, requestJson);
        if (response.statusCode() != 200) {
            String body;
            try (InputStream input = response.body()) {
                body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new RouteResult(response.statusCode(), body);
        }

        List<ObjectNode> items = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        // 5.0 pro/flash 等模型不支持流式输出，请求体不带 stream，响应是一次性 JSON
        List<SeedreamImageProtocol.Event> events = new ArrayList<>();
        if (SeedreamImageProtocol.supportsStreaming(model)) {
            try (InputStream input = response.body()) {
                SeedreamImageProtocol.readEvents(input, events::add);
            }
        } else {
            String rawBody;
            try (InputStream input = response.body()) {
                rawBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            events.addAll(SeedreamImageProtocol.parseSyncResponse(rawBody));
        }
        for (SeedreamImageProtocol.Event event : events) {
            switch (event.type()) {
                case PARTIAL_SUCCEEDED -> {
                    ObjectNode item = objectMapper.createObjectNode();
                    if (event.url() != null && !event.url().isBlank()) {
                        item.put("url", event.url());
                        items.add(item);
                    } else if (event.b64Json() != null && !event.b64Json().isBlank()) {
                        item.put("b64_json", event.b64Json());
                        items.add(item);
                    }
                }
                case PARTIAL_FAILED -> failures.add(event.message() == null ? "图片生成失败" : event.message());
                case ERROR -> throw new RuntimeException(
                        event.message() == null ? "豆包图片生成失败" : event.message());
                case COMPLETED -> completed.set(true);
                case UNKNOWN -> {
                }
            }
        }
        if (items.isEmpty()) {
            String detail = failures.isEmpty() ? "未收到图片结果" : String.join("; ", failures);
            throw new RuntimeException("豆包图片生成失败: " + detail);
        }
        if (!completed.get()) {
            log.warn("豆包 SSE 未收到 completed，保留已返回的 {} 张图片", items.size());
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        items.forEach(data::add);
        return new RouteResult(200, root.toString());
    }

    private static boolean isDoubaoModel(String model) {
        return model != null && model.startsWith(DOUBAO_MODEL_PREFIX);
    }

    private static String partText(byte[] body, String name) {
        byte[] bytes = partBytes(body, name);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static byte[] partBytes(byte[] body, String name) {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        String marker = "name=\"" + name + "\"";
        int markerIndex = text.indexOf(marker);
        if (markerIndex < 0) return null;
        int headerEnd = text.indexOf("\r\n\r\n", markerIndex);
        if (headerEnd < 0) return null;
        int valueStart = headerEnd + 4;
        String boundary = multipartBoundary(body);
        int valueEnd = text.indexOf("\r\n--" + boundary, valueStart);
        if (valueEnd < 0) return null;
        return Arrays.copyOfRange(body, valueStart, valueEnd);
    }

    private static String partContentType(byte[] body, String name) {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        String marker = "name=\"" + name + "\"";
        int markerIndex = text.indexOf(marker);
        if (markerIndex < 0) return null;
        int headerEnd = text.indexOf("\r\n\r\n", markerIndex);
        if (headerEnd < 0) return null;
        String boundary = multipartBoundary(body);
        int previousBoundary = text.lastIndexOf("\r\n--" + boundary, markerIndex);
        int partStart = previousBoundary >= 0 ? previousBoundary + 2 : 0;
        int firstLineEnd = text.indexOf("\r\n", partStart);
        String headers = firstLineEnd >= 0 ? text.substring(firstLineEnd + 2, headerEnd) : "";
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Type:", 0, 13)) {
                return line.substring(13).trim();
            }
        }
        return null;
    }

    private static String multipartBoundary(byte[] body) {
        for (int i = 2; i < body.length - 1; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n') {
                return new String(body, 2, i - 2, StandardCharsets.ISO_8859_1);
            }
        }
        throw new BusinessException(40001, "multipart body 缺少 boundary 结束符");
    }

    private static String detectImageMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";
        return "image/png";
    }

    private static byte[] buildMaskGuideImage(byte[] sourceBytes, byte[] maskBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            BufferedImage mask = ImageIO.read(new ByteArrayInputStream(maskBytes));
            if (source == null || mask == null) return null;
            int width = source.getWidth();
            int height = source.getHeight();
            BufferedImage guide = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = guide.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.setComposite(AlphaComposite.SrcOver.derive(.42f));
            graphics.setColor(new Color(255, 0, 122));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int maskX = Math.min(mask.getWidth() - 1, x * mask.getWidth() / width);
                    int maskY = Math.min(mask.getHeight() - 1, y * mask.getHeight() / height);
                    int alpha = (mask.getRGB(maskX, maskY) >>> 24) & 0xff;
                    if (alpha < 160) graphics.fillRect(x, y, 1, 1);
                }
            }
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(guide, "png", output);
            return output.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从 multipart 字节流提取 boundary，重建上游需要的 multipart Content-Type。
     * body 分隔符为 "--boundary"（前导 --），但 Content-Type 的 boundary 参数不含前导 --（multipart 规范）。
     */
    private String buildMultipartContentType(byte[] body) {
        // 前两个字节必须是 "--"（multipart 规范），否则无 boundary 可提取
        if (body.length < 2 || body[0] != '-' || body[1] != '-') {
            throw new BusinessException(40001, "multipart body 必须以 boundary 开头");
        }
        for (int i = 2; i < body.length - 1; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n') {
                // 跳过前导 "--"，取纯 boundary（Content-Type 参数规范不含 --）
                String boundary = new String(body, 2, i - 2, StandardCharsets.ISO_8859_1);
                return "multipart/form-data; boundary=" + boundary;
            }
        }
        throw new BusinessException(40001, "multipart body 缺少 boundary 结束符");
    }

    /**
     * 从 multipart 字节流中提取 name="model" 字段的值。
     * 轻量字节级解析（不引入 multipart 解析库）：
     *   定位 name="model" → 其后跟随 \r\n\r\n → 读取直到下一个 \r\n
     */
    private String parseModelField(byte[] body) {
        String text = new String(body, StandardCharsets.ISO_8859_1);  // multipart 二进制安全，逐字节映射
        String marker = "name=\"model\"";
        int idx = text.indexOf(marker);
        if (idx < 0) return null;
        int headerEnd = text.indexOf("\r\n\r\n", idx);
        if (headerEnd < 0) return null;
        int valueStart = headerEnd + 4;
        int valueEnd = text.indexOf("\r\n", valueStart);
        if (valueEnd < 0) return null;
        return text.substring(valueStart, valueEnd).trim();
    }
}
