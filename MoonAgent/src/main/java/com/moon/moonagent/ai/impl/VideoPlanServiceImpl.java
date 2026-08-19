package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.VideoPlanService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import com.storyboard.common.BusinessException;

/**
 * 图生视频方案设计服务实现 —— 视频生成前先用视觉模型"看图"。
 */
@Service
@RequiredArgsConstructor
public class VideoPlanServiceImpl implements VideoPlanService {

    private static final Logger log = LoggerFactory.getLogger(VideoPlanServiceImpl.class);

    /** System Prompt：要求视觉模型看图后输出图生视频方案（动态 prompt + 时长） */
    private static final String VIDEO_PLAN_SYSTEM_PROMPT =
            """
                    你是一名专业的视频生成方案设计师。用户会给你一张参考图片和一句视频创作诉求。
                    这张图片将作为视频的第一帧（首帧画面），画面主体、构图、环境以图片内容为准。
                    请先仔细观察图片内容（主体、构图、色调、光线、风格、环境），再结合用户诉求，
                    输出一个 JSON 对象，包含两个字段：
                    1. message（字符串，必填）：完整视频生成 prompt，中文 50~120 字，必须包含：
                       ①基于首帧画面的动态动作（画面中什么在动、怎么动）②环境与背景的延伸 ③光线、色调与氛围
                       ④运镜（从推/拉/摇/移/跟/升/降中明确选择，写明起幅到落幅）⑤景别与视角 ⑥风格
                       （电影感/写实/动画等）。注意：画面主体与构图已在首帧中确定，不要描述与图片冲突的
                       静态内容，prompt 专注「动态」——动作、运镜、氛围变化。不要写入分辨率、时长、画幅参数。
                    2. duration（数字，必填）：4~15 之间的整数，常用档位 4/6/8/12/15。用户未指定时默认 8。
                    只输出 JSON，不要输出其他内容。""";

    private final GatewayModelService gatewayModelService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient.Builder chatClientBuilder;
    /** 懒加载：首次调用时用默认视觉模型构建（@RequiredArgsConstructor 无法承载原构造器内的构建逻辑） */
    private volatile ChatClient chatClient;

    /**
     * 生成图生视频方案：视觉模型看图 + 用户诉求 → 视频 prompt + 时长。
     *
     * @param imagePath   源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest 用户视频创作诉求（如"让画面动起来，镜头缓缓推近"）
     * @return 视频方案；视觉理解失败或输出非法时抛 RuntimeException（调用方转业务错误）
     */
    @Override
    public VideoPlan buildVideoPlan(String imagePath, String userRequest, String modelOptionsText) {
        try {
            // 1. 读本地源图 → base64 data URI
            String filename = extractFilename(imagePath);
            Path localFile = fileStorageService.resolveImage(filename);
            if (!Files.exists(localFile)) {
                throw new BusinessException(40401, "资源不存在: " + localFile);
            }
            byte[] imageBytes = Files.readAllBytes(localFile);
            String dataUri = "data:image/" + guessImageType(filename) + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            // 2. 组装多模态 UserMessage：文本诉求 + 图片（data 传完整 data URI 字符串，直接透传为 image_url）
            Media media = Media.builder()
                    .mimeType(MimeType.valueOf("image/" + guessImageType(filename)))
                    .data(dataUri)
                    .build();
            UserMessage userMessage = UserMessage.builder()
                    .text("用户的视频创作诉求："
                            + (userRequest == null || userRequest.isBlank()
                                    ? "（未提供，请结合图片内容自行设计合理的动态方案）" : userRequest))
                    .media(media)
                    .build();

            // 3. 调用视觉模型（ChatClient 统一走 LLM 网关；有选项文本时追加选参指令）
            String content = chatClient()
                    .prompt()
                    .system(buildSystemPrompt(modelOptionsText))
                    .messages(userMessage)
                    .call()
                    .content();

            // 4. 解析：优先 BeanOutputConverter 结构化解析，失败走 extractVideoPlan 兜底
            VideoPlan plan = null;
            try {
                BeanOutputConverter<VideoPlan> conv = new BeanOutputConverter<>(VideoPlan.class);
                VideoPlan parsed = null;
                if (content != null) {
                    parsed = conv.convert(content);
                }
                if (parsed != null && parsed.isValid()) {
                    plan = parsed;
                }
            } catch (RuntimeException e) {
                // 结构化解析失败（AI 返回非 JSON / 字段不符等），走兜底
            }
            if (plan == null) {
                plan = extractVideoPlan(content);
            }
            if (plan == null) {
                throw new BusinessException(50201, "视觉理解未输出有效的视频方案");
            }
            log.info("图生视频方案已生成: duration={}, message 前 120 字: {}",
                    plan.duration(),
                    plan.message().length() > 120 ? plan.message().substring(0, 120) + "…" : plan.message());
            return plan;
        } catch (Exception e) {
            log.warn("图生视频方案设计失败: {}", e.getMessage());
            throw new BusinessException(50201, "图片理解失败: " + e.getMessage(), e);
        }
    }

    /**
     * 懒加载获取 ChatClient：默认模型固定为网关默认视觉模型（getDefaultVisionModel），超时 120s
     * （与原 HttpClient timeout 一致）；双重检查锁保证线程安全。
     */
    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultVisionModel())
                                    .timeout(Duration.ofSeconds(120)))
                            .build();
                }
            }
        }
        return chatClient;
    }

    /** 组装 system prompt：基础方案指令 + 可选的模型参数选择指令（有选项文本时要求 LLM 预选参数并给理由） */
    private String buildSystemPrompt(String modelOptionsText) {
        if (modelOptionsText == null || modelOptionsText.isBlank()) {
            return VIDEO_PLAN_SYSTEM_PROMPT;
        }
        return VIDEO_PLAN_SYSTEM_PROMPT
                + "\n3. params（对象，必填）：从以下可用模型与参数选项中选择合适的值：\n"
                + modelOptionsText
                + "（形如 {\"model\":\"...\",\"resolution\":\"...\",\"aspectRatio\":\"...\"}，值必须是选项中出现的）\n"
                + "4. reasons（对象，必填）：每个参数一句简短推荐理由（≤15 字），键与 params 一致。\n"
                + "只输出 JSON，不要输出其他内容。";
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
                return new VideoPlan(message.trim(), duration,
                        parseStringMap(node, "params"), parseStringMap(node, "reasons"));
            }
        } catch (Exception ignored) {
            // 非 JSON 输出，降级 null（调用方转业务错误）
        }
        return null;
    }

    /** 从 JSON 节点提取字符串 Map（对象字段 → Map；缺失/非对象返回空 Map） */
    private Map<String, String> parseStringMap(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        node.get(field).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
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
