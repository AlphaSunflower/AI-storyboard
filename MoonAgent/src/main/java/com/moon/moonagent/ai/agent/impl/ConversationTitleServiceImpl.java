package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;

/**
 * 会话标题异步生成服务。
 *
 * 职责：新会话首条消息发出后，用大模型（不思考模式）根据首条用户消息生成简短标题，
 * 并条件更新 conversations.title（仅当标题仍为默认值「新对话」时，避免覆盖用户手动重命名）。
 *
 * 设计要点：
 * - 全程在虚拟线程中异步执行，任何失败只记日志、绝不抛出（不阻塞对话主流程）；
 * - 模型由 {@code ai.agent.title-model} 显式指定，默认 mimo-v2.6-pro；留空才回退网关默认文本模型
 *   （默认文本模型可能随时被后台切换，不能作为标题稳定性的隐式依赖）；
 * - thinking_level 不思考参数（"minimal"，当前标题模型接受，语义自文档化）通过 Spring AI
 *   OpenAiChatOptions.extraBody 透传——字节码级验证会 merge 进 ChatCompletionCreateParams 的
 *   additionalBodyProperties，即请求体顶层字段（与原手写 body.put("thinking_level", ...) 等价）；
 * - 先用首句写入确定性兜底标题，AI 成功后再条件替换。这样模型超时/空内容/无路由时，
 *   用户仍能得到标题，而不是一直停在「新对话」；
 * - 更新用 LambdaUpdateWrapper 只动 title/updatedAt 两列，并带「仍为默认值」原子条件——
 *   编排线程持有同一 AgentConversation 实体实例并会整实体 updateById（回填
 *
 * 实现说明：已从手写 JDK HttpClient 直连网关 /v1/chat/completions 改为
 * Spring AI ChatClient（spring.ai.openai.base-url 已指向网关 /v1，纯文本调用，无结构化输出）。
 */
@Service
@RequiredArgsConstructor
public class ConversationTitleServiceImpl implements ConversationTitleService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTitleServiceImpl.class);

    /** 默认标题：仅当会话标题仍是该值时才会被 AI 重命名覆盖 */
    private static final String DEFAULT_TITLE = "新对话";


    /** 标题生成 prompt：约束输出为 6-15 字中文（或 3-8 英文词）的纯标题 */

    private final AgentConversationMapper conversationMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentAiConfigProperties agentConfig;
    private final com.moon.moonagent.config.PromptConfig promptConfig;

    /**
     * 懒加载 ChatClient：标题是锦上添花，超时比脚本生成（120s）收紧，不值得长等（与原 HttpClient timeout 一致）。
     * 构造器内构建逻辑迁移至懒加载方法，统一 @RequiredArgsConstructor 注入。
     */
    private volatile ChatClient chatClient;

    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .timeout(Duration.ofSeconds(
                                            Math.max(1, agentConfig.getTitleTimeoutSeconds()))))
                            .build();
                }
            }
        }
        return chatClient;
    }

    /**
     * 首条消息异步重命名：首句兜底立即落库 → AI 生成 → 条件替换。
     * 任何失败仅记日志，绝不抛出（异步线程异常不影响对话主流程）。
     */
    public void renameOnFirstMessage(String conversationId, String firstUserContent) {
        String fallback = fallbackTitle(firstUserContent);
        boolean fallbackApplied = false;
        if (fallback != null) {
            try {
                fallbackApplied = applyTitle(conversationId, DEFAULT_TITLE, fallback);
            } catch (Exception e) {
                log.warn("写入首句兜底标题失败: conversationId={}, error={}",
                        conversationId, e.getMessage());
            }
        }

        String generated = null;
        int attempts = Math.max(1, agentConfig.getTitleMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                generated = generateTitle(firstUserContent);
                if (generated != null && !generated.isBlank()) break;
                log.warn("标题生成结果为空: conversationId={}, attempt={}/{}",
                        conversationId, attempt, attempts);
            } catch (Exception e) {
                log.warn("异步 AI 生成会话标题失败: conversationId={}, attempt={}/{}, error={}",
                        conversationId, attempt, attempts, e.getMessage());
            }
        }
        if (generated == null || generated.isBlank()) {
            if (fallbackApplied) {
                log.info("会话标题已使用首句兜底: conversationId={}, title={}",
                        conversationId, fallback);
            }
            return;
        }
        try {
            if (applyTitle(conversationId, fallbackApplied ? fallback : DEFAULT_TITLE, generated)) {
                log.info("会话标题已由 AI 重命名: conversationId={}, title={}",
                        conversationId, generated);
            }
        } catch (Exception e) {
            log.warn("写入 AI 会话标题失败，保留首句兜底标题: conversationId={}, error={}",
                    conversationId, e.getMessage());
        }
    }

    /** 调 LLM 网关生成标题（配置模型 + thinking_level 经 extraBody 显式声明） */
    private String generateTitle(String userContent) {
        // 首条消息截断到 200 字：标题生成只需要主题线索，避免超长输入拖慢/抬高成本
        String truncated = userContent != null && userContent.length() > 200
                ? userContent.substring(0, 200) : userContent;
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(resolveTitleModel())
                .timeout(Duration.ofSeconds(Math.max(1, agentConfig.getTitleTimeoutSeconds())))
                .maxTokens(256)
                .extraBody(Map.of("thinking_level", "minimal"));
        String raw = chatClient().prompt()
                .options(options)
                .system(promptConfig.get("services/conversation-title"))
                .user(truncated)
                .call()
                .content();
        return cleanTitle(raw);
    }

    /** 显式配置优先；配置为空时才跟随网关当前默认文本模型。 */
    String resolveTitleModel() {
        String configured = agentConfig.getTitleModel();
        return configured == null || configured.isBlank()
                ? gatewayModelService.getDefaultTextModel()
                : configured.trim();
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
     * 首句兜底标题：清理常见 Markdown/链接，取第一个句末标点前的文本并截断。
     * LLM 不可用时仍让首条消息产生可用标题，不把会话永久留在“新对话”。
     */
    static String fallbackTitle(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[`*_>#~]+", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) return null;

        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            if ("。！？!?；;".indexOf(text.charAt(i)) >= 0) {
                end = i;
                break;
            }
        }
        String title = text.substring(0, end).trim();
        if (title.isBlank()) title = text;
        if (title.length() > 24) title = title.substring(0, 24).trim();
        return title.isBlank() ? null : title;
    }

    /**
     * 条件更新：仅当 title 仍等于 expectedTitle 时更新 title + updatedAt（不触碰其他字段）。
     * .eq(title, expectedTitle) 在 SQL 层原子解决「已被用户手动改过 / 已被其他线程更新」的竞态；
     * 更新行数 0 说明已被改过，仅 debug 记录。
     */
    private boolean applyTitle(String conversationId, String expectedTitle, String title) {
        if (title == null || title.isBlank()) return false;
        int rows = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getTitle, expectedTitle)
                .set(AgentConversation::getTitle, title)
                .set(AgentConversation::getUpdatedAt, OffsetDateTime.now()));
        if (rows <= 0) {
            // 标题已被用户手动改过（或已被其他线程更新），不做任何覆盖
            log.debug("会话标题状态已变化，跳过条件更新: conversationId={}, expectedTitle={}",
                    conversationId, expectedTitle);
        }
        return rows > 0;
    }
}
