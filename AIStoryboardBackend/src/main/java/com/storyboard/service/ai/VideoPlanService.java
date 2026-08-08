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
 * 图生视频方案设计服务 —— 视频生成前先用视觉模型"看图"。
 *
 * <p>背景：Dify 工作流的 DeepSeek 无视觉能力，文生视频方案设计节点只能基于用户口述写
 * prompt；而图生视频的首帧是用户上传的参考图，画面主体/构图/环境都以图为准，盲写 prompt
 * 会与真实画面脱节。本服务把视觉理解放到后端：视觉模型（默认 gemini-3-flash-preview）
 * 直接看源图 + 用户诉求，输出结构化视频方案（动态 prompt + 时长），再投喂给 MiniMax
 * 图生视频——保证方案与参考图对齐。
 *
 * <p>调用链（Agent 对话图生视频路径）：Dify 工作流「视频类型分流」判断携带参考图 →
 * 信号 answer 节点「后端执行图生视频方案设计」→ 后端 {@code triggerAutoVideoPlan} →
 * 本服务生成视频方案 → SSE 推 {@code video_plan} 事件 → 前端确认卡片 →
 * 「开始生成视频」→ MiniMax 图生视频。
 *
 * <p>设计要点（与 {@link ImageRefinePromptService} 对齐）：
 * <ul>
 *   <li>模型固定 {@link AiConfigProperties#getDefaultVisionModel()}（gemini-3-flash-preview，
 *       支持视觉分析；不传 thinking_level——实测老张网关对 preview 系不透传思考参数）；</li>
 *   <li>源图从本地 uploads 读取转 base64 data URI 内联（参照 MiniMax 图生视频做法，无需上传公网）；</li>
 *   <li>输出结构化为 JSON {@code {message, duration}}，{@code message} 直接作为图生视频
 *       prompt（首帧语义：画面主体/构图以首帧图为准，prompt 专注动态动作、运镜、光线氛围）；</li>
 *   <li>超时 120s（视觉理解 + 大图 base64 传输，给足余量）。</li>
 * </ul>
 */
@Service
public class VideoPlanService {

    private static final Logger log = LoggerFactory.getLogger(VideoPlanService.class);

    /** System Prompt：要求视觉模型看图后输出图生视频方案（动态 prompt + 时长） */
    private static final String VIDEO_PLAN_SYSTEM_PROMPT =
        "你是一名专业的视频生成方案设计师。用户会给你一张参考图片和一句视频创作诉求。\n"
        + "这张图片将作为视频的第一帧（首帧画面），画面主体、构图、环境以图片内容为准。\n"
        + "请先仔细观察图片内容（主体、构图、色调、光线、风格、环境），再结合用户诉求，\n"
        + "输出一个 JSON 对象，包含两个字段：\n"
        + "1. message（字符串，必填）：完整视频生成 prompt，中文 50~120 字，必须包含：\n"
        + "   ①基于首帧画面的动态动作（画面中什么在动、怎么动）②环境与背景的延伸 ③光线、色调与氛围\n"
        + "   ④运镜（从推/拉/摇/移/跟/升/降中明确选择，写明起幅到落幅）⑤景别与视角 ⑥风格\n"
        + "   （电影感/写实/动画等）。注意：画面主体与构图已在首帧中确定，不要描述与图片冲突的\n"
        + "   静态内容，prompt 专注「动态」——动作、运镜、氛围变化。不要写入分辨率、时长、画幅参数。\n"
        + "2. duration（数字，必填）：4~15 之间的整数，常用档位 4/6/8/12/15。用户未指定时默认 8。\n"
        + "只输出 JSON，不要输出其他内容。";

    private final AiConfigProperties config;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public VideoPlanService(AiConfigProperties config, FileStorageService fileStorageService) {
        this.config = config;
        this.fileStorageService = fileStorageService;
    }

    /** 图生视频方案：message=视频 prompt（直接投喂生成），duration=时长（秒） */
    public record VideoPlan(String message, Integer duration) {
        /** 校验合法性：message 非空、duration 在 4~15 区间；非法返回 null 由调用方降级 */
        public boolean isValid() {
            if (message == null || message.isBlank()) return false;
            return duration != null && duration >= 4 && duration <= 15;
        }
    }

    /**
     * 生成图生视频方案：视觉模型看图 + 用户诉求 → 视频 prompt + 时长。
     *
     * @param imagePath   源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest 用户视频创作诉求（如"让画面动起来，镜头缓缓推近"）
     * @return 视频方案；视觉理解失败或输出非法时抛 RuntimeException（调用方转业务错误）
     */
    public VideoPlan buildVideoPlan(String imagePath, String userRequest) {
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

            // 2. 构造 OpenAI 兼容多模态请求（文本诉求 + 图片）
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getDefaultVisionModel());
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", VIDEO_PLAN_SYSTEM_PROMPT));
            Map<String, Object> textPart = Map.of("type", "text",
                    "text", "用户的视频创作诉求："
                            + (userRequest == null || userRequest.isBlank()
                                    ? "（未提供，请结合图片内容自行设计合理的动态方案）" : userRequest));
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

            // 3. 解析结构化 JSON {message, duration}
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            VideoPlan plan = extractVideoPlan(content);
            if (plan == null) {
                throw new RuntimeException("视觉理解未输出有效的视频方案");
            }
            log.info("图生视频方案已生成: duration={}, message 前 120 字: {}",
                    plan.duration(),
                    plan.message().length() > 120 ? plan.message().substring(0, 120) + "…" : plan.message());
            return plan;
        } catch (Exception e) {
            log.warn("图生视频方案设计失败: {}", e.getMessage());
            throw new RuntimeException("图片理解失败: " + e.getMessage(), e);
        }
    }

    /** 从模型输出提取视频方案：JSON 结构优先（message/duration），非法返回 null */
    private VideoPlan extractVideoPlan(String content) {
        if (content == null || content.isBlank()) return null;
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
                String message = node.path("message").asText("");
                Integer duration = node.path("duration").isNumber() ? node.path("duration").asInt() : null;
                if (message.isBlank() || duration == null || duration < 4 || duration > 15) {
                    log.warn("视觉理解输出视频方案字段非法: message={}, duration={}",
                            message.length() > 60 ? message.substring(0, 60) + "…" : message, duration);
                    return null;
                }
                return new VideoPlan(message.trim(), duration);
            }
        } catch (Exception ignored) {
            // 非 JSON 输出，降级 null（调用方转业务错误）
        }
        return null;
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
