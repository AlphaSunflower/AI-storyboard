package com.storyboard.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词优化服务。
 *
 * 职责：把用户输入的需求草稿（≥6 字符）优化为专业的分镜提示词。
 * 优化方向由 LLM 自行判断（草稿可能是剧情脚本、图片设计或视频设计需求，也可能是综合需求），
 * 输出一段优化后的提示词文本（不强制 JSON 结构，规避解析失败风险）。
 *
 * 设计要点：
 * - 纯文本转换工具：不落库、不关联会话，调用方（AgentConversationController）直接返回；
 * - 模型固定用 {@link AiConfigProperties#getDefaultVisionModel()}（质量优先，用户确认；
 *   不传 thinking_level——实测老张网关对 preview 系模型不透传思考参数，传了无意义）；
 * - 超时 60s：用户主动触发、可接受等待，一次性返回（不做流式）；
 * - 任何失败包装为 RuntimeException 由 Controller 统一转错误码，不静默吞错（与标题
 *   被动静默不同：优化是用户显式操作，失败必须可见）。
 */
@Service
public class PromptOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(PromptOptimizeService.class);

    /**
     * 优化 System Prompt：LLM 自行判断草稿类型（剧情/图片/视频/综合），
     * 输出一段优化后的专业提示词；不要求 JSON，直接给提示词文本。
     */
    private static final String OPTIMIZE_PROMPT =
        "你是一名专业的分镜提示词优化师。用户会给你一段需求草稿，可能是剧情脚本、"
        + "图片设计或视频设计需求，也可能是综合需求。请你自行判断其类型，"
        + "输出一段优化后的专业提示词：剧情类给出完整脉络与情绪基调；图片类给出构图、"
        + "主体、环境、光线、色彩、风格、镜头类型；视频类给出运镜、节奏、转场、画面动势、时长感。"
        + "直接输出优化后的提示词本身，不要 JSON、不要解释、不要编号前缀。";

    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public PromptOptimizeService(AiConfigProperties config) {
        this.config = config;
    }

    /** 优化草稿为专业提示词（LLM 自判类型，单文本输出）。失败抛 RuntimeException（Controller 统一转错误码）。 */
    public String optimize(String content) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getDefaultVisionModel()); // 质量优先（用户确认）
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", OPTIMIZE_PROMPT));
            // 草稿截断 500 字：提示词优化只需要核心需求，防超长输入拖慢
            messages.add(Map.of("role", "user", "content",
                    content.length() > 500 ? content.substring(0, 500) : content));
            body.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlVision()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                // 优化任务用户主动等待，60s 内一次性返回（不做流式）
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("提示词优化 API 返回 " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String optimized = root.path("choices").get(0).path("message").path("content").asText("").trim();
            if (optimized.isBlank()) {
                throw new RuntimeException("优化结果为空");
            }
            return optimized;
        } catch (Exception e) {
            log.warn("提示词优化失败: {}", e.getMessage());
            throw new RuntimeException("提示词优化失败: " + e.getMessage(), e);
        }
    }
}
