package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.ImageRefinePromptService;
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
import com.storyboard.common.BusinessException;

/**
 * 图片完善提示词增强服务实现 —— 图生图前先用视觉模型"看图"。
 */
@Service
@RequiredArgsConstructor
public class ImageRefinePromptServiceImpl implements ImageRefinePromptService {

    private static final Logger log = LoggerFactory.getLogger(ImageRefinePromptServiceImpl.class);

    /** System Prompt：要求视觉模型结构化输出（现状/修改点/改图提示词） */
    /**
     * 结构化输出定义（字段名与 AI 返回 JSON 键一致，snake_case）。
     * modifications 用 Object：模型可能输出数组（修改点列表）或字符串，两种形状都能反序列化，
     * 避免 String 遇到数组抛 MismatchedInputException 导致结构化路径白走（该字段本服务不消费）。
     */
    public record RefinePlan(String image_analysis, Object modifications, String refined_prompt) {}

    private final GatewayModelService gatewayModelService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient.Builder chatClientBuilder;
    private final com.moon.moonagent.config.PromptConfig promptConfig;
    /** 懒加载：首次调用时用默认视觉模型构建（@RequiredArgsConstructor 无法承载原构造器内的构建逻辑） */
    private volatile ChatClient chatClient;

    /**
     * 生成图生图改图提示词：视觉模型看图 + 用户诉求 → refined_prompt。
     *
     * @param imagePath  源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest 用户完善诉求（如"太暗了，改亮一点"）
     * @return refined_prompt 改图提示词文本
     */
    @Override
    public String buildRefinedPrompt(String imagePath, String userRequest) {
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
                    .text("用户的修改诉求：" + (userRequest == null || userRequest.isBlank()
                            ? "（未提供，请结合图片自行判断合理的优化方向）" : userRequest))
                    .media(media)
                    .build();

            // 3. 调用视觉模型（ChatClient 统一走 LLM 网关）
            String content = chatClient()
                    .prompt()
                    .system(promptConfig.get("services/image-refine-prompt"))
                    .messages(userMessage)
                    .call()
                    .content();

            // 4. 解析：优先取结构化 refined_prompt；解析失败降级取全文
            try {
                BeanOutputConverter<RefinePlan> conv = new BeanOutputConverter<>(RefinePlan.class);
                RefinePlan plan = null;
                if (content != null) {
                    plan = conv.convert(content);
                }
                if (plan != null && plan.refined_prompt() != null && !plan.refined_prompt().isBlank()) {
                    return plan.refined_prompt().trim();
                }
            } catch (RuntimeException e) {
                // 结构化解析失败（AI 返回非 JSON / 字段不符等），走兜底
            }
            return extractRefinedPrompt(content);
        } catch (Exception e) {
            log.warn("图片完善提示词增强失败: {}", e.getMessage());
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
