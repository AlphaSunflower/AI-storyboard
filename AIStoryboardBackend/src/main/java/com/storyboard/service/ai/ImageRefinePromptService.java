package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.service.FileStorageService;
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
 *   <li>已换 Spring AI ChatClient 多模态（Media + UserMessage），替代原手写 JDK HttpClient 调用。</li>
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

    /**
     * 结构化输出定义（字段名与 AI 返回 JSON 键一致，snake_case）。
     * modifications 用 Object：模型可能输出数组（修改点列表）或字符串，两种形状都能反序列化，
     * 避免 String 遇到数组抛 MismatchedInputException 导致结构化路径白走（该字段本服务不消费）。
     */
    public record RefinePlan(String image_analysis, Object modifications, String refined_prompt) {}

    private final AiConfigProperties config;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient chatClient;

    public ImageRefinePromptService(AiConfigProperties config, FileStorageService fileStorageService,
                                    ChatClient.Builder chatClientBuilder) {
        this.config = config;
        this.fileStorageService = fileStorageService;
        // 默认模型固定为 config.getDefaultVisionModel()，超时 120s（与原 HttpClient timeout 一致）
        this.chatClient = chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.getDefaultVisionModel())
                        .timeout(Duration.ofSeconds(120)))
                .build();
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

            // 2. 组装多模态 UserMessage：文本诉求 + 图片（data 传完整 data URI 字符串，直接透传为 image_url）
            Media media = Media.builder()
                    .mimeType(MimeType.valueOf("image/" + guessImageType(filename)))
                    .data(dataUri)
                    .build();
            UserMessage userMessage = UserMessage.builder()
                    .text("用户的修改诉求：" + (userRequest == null || userRequest.isBlank()
                            ? "（未提供，请结合图片自行判断合理的优化方向）" : userRequest))
                    .media(media)
                    .build();

            // 3. 调用视觉模型（ChatClient 统一走 LLM 网关）
            String content = chatClient.prompt()
                    .system(REFINE_SYSTEM_PROMPT)
                    .messages(userMessage)
                    .call()
                    .content();

            // 4. 解析：优先取结构化 refined_prompt；解析失败降级取全文
            try {
                BeanOutputConverter<RefinePlan> conv = new BeanOutputConverter<>(RefinePlan.class);
                RefinePlan plan = conv.convert(content);
                if (plan != null && plan.refined_prompt() != null && !plan.refined_prompt().isBlank()) {
                    return plan.refined_prompt().trim();
                }
            } catch (RuntimeException e) {
                // 结构化解析失败（AI 返回非 JSON / 字段不符等），走兜底
            }
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
