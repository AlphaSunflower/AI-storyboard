package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片完善提示词增强服务 —— 图生图前先用视觉模型"看图"。
 *
 * <p>背景：Dify 工作流的 DeepSeek 无视觉能力，之前「完善图片设计方案」节点只能基于
 * 用户口述 + 上次文字 prompt 盲猜改图方案，与真实图片脱节。本服务把视觉理解放到后端：
 * 视觉模型（默认 gemini-3-flash-preview）直接看源图 + 用户诉求，输出结构化改图提示词
 * （图片现状 / 修改点 / 改后效果），再投喂给图生图 edits 接口——保证改图提示词与真实图片对齐。
 *
 * <p>调用链（Agent 对话完善图片路径）：Dify 工作流删掉了「完善图片设计方案」LLM 与 HITL 确认，
 * 由信号节点触发后端 → 本服务生成 refined_prompt → {@link ImageGenerationService}（mode=edit）图生图。
 *
 * <p>设计要点：
 * <ul>
 *   <li>模型固定 {@link AiConfigProperties#getDefaultVisionModel()}（gemini-3-flash-preview，
 *       支持视觉分析；不传 thinking_level——实测老张网关对 preview 系不透传思考参数）；</li>
 *   <li>源图从本地 uploads 读取转 base64 data URI 内联（参照 MiniMax 图生视频做法，无需上传公网）；</li>
 *   <li>输出结构化为 JSON {@code {image_analysis, modifications, refined_prompt}}，
 *       {@code refined_prompt} 直接投喂图生图；可排查"模型理解了什么、决定改什么"；</li>
 *   <li>超时 120s（视觉理解 + 大图 base64 传输，给足余量）。</li>
 * </ul>
 */
@Service
public class ImageRefinePromptService {

    private static final Logger log = LoggerFactory.getLogger(ImageRefinePromptService.class);

    /** System Prompt：要求视觉模型结构化输出（现状/修改点/改图提示词） */
    private static final String REFINE_SYSTEM_PROMPT =
        "你是一名专业的图片编辑提示词设计师。用户会给你一张图片和一句修改诉求。\n"
        + "请先仔细观察图片内容（主体、构图、色调、光线、风格、环境），再结合用户诉求，"
        + "输出一个 JSON 对象，包含三个字段：\n"
        + "1. image_analysis：对图片现状的简要描述（你实际看到了什么）；\n"
        + "2. modifications：根据用户诉求确定的修改点列表（要改什么、怎么改）；\n"
        + "3. refined_prompt：一段可直接投喂给图生图模型的完整改图提示词（中文，包含："
        + "保留的既有元素 + 修改点 + 修改后期望效果 + 风格/光线/构图约束）。\n"
        + "只输出 JSON，不要输出其他内容。";

    private final AiConfigProperties config;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ImageRefinePromptService(AiConfigProperties config, FileStorageService fileStorageService) {
        this.config = config;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 生成图生图改图提示词：视觉模型看图 + 用户诉求 → refined_prompt。
     *
     * @param imagePath  源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest 用户完善诉求（如"太暗了，改亮一点"）
     * @return refined_prompt 改图提示词文本
     */
    public String buildRefinedPrompt(String imagePath, String userRequest) {
        try {
            // 1. 读本地源图 → base64 data URI
            String filename = extractFilename(imagePath);
            Path localFile = fileStorageService.resolveImage(filename);
            if (!Files.exists(localFile)) {
                throw new RuntimeException("源图文件不存在: " + localFile);
            }
            byte[] imageBytes = Files.readAllBytes(localFile);
            String dataUri = "data:image/" + guessImageType(filename) + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            // 2. 构造 OpenAI 兼容多模态请求
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getDefaultVisionModel());
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", REFINE_SYSTEM_PROMPT));
            // user content 为多模态数组：文本诉求 + 图片
            Map<String, Object> textPart = Map.of("type", "text",
                    "text", "用户的修改诉求：" + (userRequest == null || userRequest.isBlank() ? "（未提供，请结合图片自行判断合理的优化方向）" : userRequest));
            Map<String, Object> imagePart = Map.of("type", "image_url",
                    "image_url", Map.of("url", dataUri));
            messages.add(Map.of("role", "user", "content", List.of(textPart, imagePart)));
            body.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                // chat 调用统一走 LLM 网关（/v1/chat/completions），Authorization 换网关 Key
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("视觉理解 API 返回 " + resp.statusCode() + ": " + resp.body());
            }

            // 3. 解析：优先取结构化 refined_prompt；解析失败降级取全文
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            return extractRefinedPrompt(content);
        } catch (Exception e) {
            log.warn("图片完善提示词增强失败: {}", e.getMessage());
            throw new RuntimeException("图片理解失败: " + e.getMessage(), e);
        }
    }

    /** 从模型输出中提取 refined_prompt：JSON 结构优先，失败降级为原文 */
    private String extractRefinedPrompt(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            String json = content;
            // 剥离 markdown 代码块
            if (json.contains("```")) {
                json = json.replaceAll("```(?:json)?", "").trim();
            }
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
                String refined = node.path("refined_prompt").asText("");
                if (!refined.isBlank()) {
                    log.info("视觉理解结构化输出: image_analysis={}, modifications={}",
                            node.path("image_analysis").asText("").length() > 80
                                    ? node.path("image_analysis").asText("").substring(0, 80) + "…"
                                    : node.path("image_analysis").asText(""),
                            node.path("modifications").toString().length() > 120
                                    ? node.path("modifications").toString().substring(0, 120) + "…"
                                    : node.path("modifications").toString());
                    return refined.trim();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 输出，降级为原文
        }
        return content.trim();
    }

    /** 从 URL 路径中提取文件名（/api/files/images/abc.png → abc.png） */
    private String extractFilename(String urlPath) {
        int idx = urlPath.lastIndexOf('/');
        return idx >= 0 ? urlPath.substring(idx + 1) : urlPath;
    }

    /** 根据文件名推测图片类型（用于 data URI 的 MIME） */
    private String guessImageType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpeg";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".gif")) return "gif";
        return "png";
    }
}
