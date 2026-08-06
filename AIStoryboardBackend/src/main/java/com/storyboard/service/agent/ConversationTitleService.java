package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentConversation;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话标题异步生成服务。
 *
 * 职责：新会话首条消息发出后，用大模型（不思考模式）根据首条用户消息生成简短标题，
 * 并条件更新 conversations.title（仅当标题仍为默认值「新对话」时，避免覆盖用户手动重命名）。
 *
 * 设计要点：
 * - 全程在虚拟线程中异步执行，任何失败只记日志、绝不抛出（不阻塞 Dify 对话主流程）；
 * - 模型固定复用 {@link AiConfigProperties#getDefaultVisionModel()}（gemini flash 系）；
 * - 请求体附带 thinking_level 不思考参数（Flash 系 minimal 为最低思考级别，见常量注释）；
 * - 更新用 LambdaUpdateWrapper 只动 title/updatedAt 两列，并带「仍为默认值」原子条件——
 *   Dify 线程持有同一 AgentConversation 实体实例并会整实体 updateById（回填
 *   difyConversationId），此处若复用该实例整实体更新会把对方刚写的新字段冲掉。
 */
@Service
public class ConversationTitleService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);

    /** 默认标题：仅当会话标题仍是该值时才会被 AI 重命名覆盖 */
    private static final String DEFAULT_TITLE = "新对话";

    /**
     * 不思考模式思考级别：minimal 为 Gemini 3 Flash 系独有最低级别（Pro 最低只能 low）。
     * 老张 OpenAI 兼容网关的透传写法以控制台为准，联调验证时若返回 400，
     * 改为 "thinking": false（OpenAI 兼容通用写法）重试；仍不接受则移除该字段。
     * 验证结果出来后把实际可用写法固化到本注释与 CLAUDE.md。
     */
    private static final String TITLE_THINKING_LEVEL = "minimal";

    /** 标题生成 prompt：约束输出为 6-15 字中文（或 3-8 英文词）的纯标题 */
    private static final String TITLE_PROMPT =
        "你是一名对话标题命名助手。根据用户的第一条消息，为这段 AI 对话生成一个简洁标题。"
        + "要求：6-15 个汉字（或 3-8 个英文单词）；概括对话主题；不要标点、引号、书名号；"
        + "不要“对话”“聊天”“标题”等字眼；只输出标题本身，不要任何解释或前后缀。\n\n用户消息：";

    private final AgentConversationMapper conversationMapper;
    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ConversationTitleService(AgentConversationMapper conversationMapper,
                                    AiConfigProperties config) {
        this.conversationMapper = conversationMapper;
        this.config = config;
    }

    /**
     * 首条消息异步重命名：生成标题 → 条件更新。
     * 任何失败仅记日志，绝不抛出（异步线程异常不影响对话主流程）。
     */
    public void renameOnFirstMessage(String conversationId, String firstUserContent) {
        try {
            String title = generateTitle(firstUserContent);
            if (title == null || title.isBlank()) {
                log.warn("标题生成结果为空，放弃重命名: conversationId={}", conversationId);
                return;
            }
            applyTitle(conversationId, title);
        } catch (Exception e) {
            log.warn("异步重命名会话标题失败(不影响对话): conversationId={}, error={}",
                    conversationId, e.getMessage());
        }
    }

    /** 调 Laozhang chat completions 生成标题（固定 defaultVisionModel + 不思考模式） */
    private String generateTitle(String userContent) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getDefaultVisionModel());
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", TITLE_PROMPT));
            // 首条消息截断到 200 字：标题生成只需要主题线索，避免超长输入拖慢/抬高成本
            messages.add(Map.of("role", "user", "content",
                    userContent != null && userContent.length() > 200
                            ? userContent.substring(0, 200) : userContent));
            body.put("messages", messages);
            // 不思考模式（联调验证实际写法，见常量注释）
            body.put("thinking_level", TITLE_THINKING_LEVEL);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlVision()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                // 标题是锦上添花，超时比脚本生成（120s）收紧，不值得长等
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("标题生成 API 返回 " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String raw = root.path("choices").get(0).path("message").path("content").asText("");
            return cleanTitle(raw);
        } catch (Exception e) {
            throw new RuntimeException("AI 生成会话标题失败: " + e.getMessage(), e);
        }
    }

    /** 清洗：trim / 去首尾引号 / 去换行 / 截断 30 字。空白返回 null（调用方放弃更新） */
    String cleanTitle(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        // 剥掉成对的常见引号："" '' 「」 “”
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '「' && last == '」') || (first == '“' && last == '”')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        // 去除换行/制表符，避免标题里出现控制字符破坏列表布局
        t = t.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isBlank() ? null : t;
    }

    /**
     * 条件更新：仅当 title 仍为默认值时更新 title + updatedAt（不触碰其他字段）。
     * .eq(title, DEFAULT_TITLE) 在 SQL 层原子解决「已被用户手动改过 / 已被其他线程更新」的竞态；
     * 更新行数 0 说明已被改过，仅 debug 记录。
     */
    private void applyTitle(String conversationId, String title) {
        int rows = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getTitle, DEFAULT_TITLE)
                .set(AgentConversation::getTitle, title)
                .set(AgentConversation::getUpdatedAt, OffsetDateTime.now()));
        if (rows > 0) {
            log.info("会话标题已由 AI 重命名: conversationId={}, title={}", conversationId, title);
        } else {
            // 标题已被用户手动改过（或已被其他线程更新），不做任何覆盖
            log.debug("会话标题已被修改，跳过 AI 重命名: conversationId={}", conversationId);
        }
    }
}
