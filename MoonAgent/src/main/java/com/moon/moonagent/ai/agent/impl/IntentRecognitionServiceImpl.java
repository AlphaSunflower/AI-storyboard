package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.IntentResult;
import com.moon.moonagent.entity.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.moon.moonagent.ai.GatewayModelService;

/**
 * 意图识别服务：把意图识别提取到后端。
 *
 * 职责：用户发消息时，后端调 LLM 网关识别用户输入意图 → 返回 type（intent-aisplit /
 * intent-pic / intent-video / intent-other），随请求的 inputs 传给
 * start 节点 type 变量，「意图路由」if-else 直接按 type 分流（工作流侧已删除「意图识别」LLM 节点）。
 *
 * 设计要点：
 * - 复用 ConversationTitleService 的 Spring AI ChatClient 调用模式（纯文本调用，
 *   spring.ai.openai.base-url 已指向网关 /v1）；
 * - 模型从网关动态获取（fast 档分类任务足够）；
 * - 输出约束为纯意图标识符（如 intent-pic），解析后白名单校验，非法兜底 intent-other；
 * - 任何异常（网络/解析/超时）兜底 intent-other，绝不阻塞对话主流程；
 * - 历史上下文：最多携带最近 {@link #HISTORY_LIMIT} 条消息，支撑"继续/接着上次"判断。
 */
@Service
@RequiredArgsConstructor
public class IntentRecognitionServiceImpl implements IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionServiceImpl.class);


    /** 历史上下文：最多取最近 8 条消息（支撑"继续/接着上次"判断） */
    public static final int HISTORY_LIMIT = 8;

    /** 兜底意图：识别失败/解析失败/白名单外 → 引导分支，不阻塞对话 */
    public static final String FALLBACK_TYPE = "intent-other";

    /** 合法意图集（由 Skill 推导：各 skill.intentType + 兜底意图） */
    private Set<String> validTypes;

    /**
     * 规则前置匹配表：强关键词命中直接路由（免一次 LLM 调用）。
     * 由 Skill 推导（priority 升序 = delete 先于 aisplit 的「分镜」词之前，否则「删除分镜」误入 aisplit 生成链）。
     * 歧义/未命中交给 LLM 判断（LLM prompt 仍含完整分类规则）。
     */
    private List<Map.Entry<String, Pattern>> ruleTable;

    /**
     * 意图识别 prompt：移植自工作流「意图识别」节点。
     * 与原版差异：只输出 type（意图标识符），不再生成 message（后端只透传 type）。
     */

    /** 宽松提取 JSON 中的 {type, confidence}（LLM 常带空格/代码块包裹，正则兜底） */
    private static final java.util.regex.Pattern INTENT_JSON = java.util.regex.Pattern.compile(
            "\"type\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"confidence\"\\s*:\\s*([0-9.]+)");

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final com.moon.moonagent.config.PromptConfig promptConfig;
    private final SkillManager skillManager;

    /** 由 Skill 推导规则前置表 + 合法意图集（priority 升序：delete 先于 aisplit 的「分镜」；menu 顺序用于澄清菜单） */
    @PostConstruct
    void initRules() {
        List<Map.Entry<String, Pattern>> rules = new ArrayList<>();
        Set<String> types = new java.util.LinkedHashSet<>();
        for (AgentSkill skill : skillManager.allOrdered()) {  // priority 升序（静态 + 动态）
            if (!skill.triggers().isEmpty()) {
                for (String t : skill.triggers()) {
                    if (t == null || t.isBlank()) continue;
                    try { rules.add(Map.entry(skill.intentType(), Pattern.compile(t))); } catch (Exception e) {
                        log.warn("意图规则编译失败: intent={}, trigger={}", skill.intentType(), t);
                    }
                }
            }
            types.add(skill.intentType());
        }
        types.add(FALLBACK_TYPE);
        ruleTable = rules;
        validTypes = types;
        log.info("意图识别规则表已加载: {} 条规则, {} 个合法意图", ruleTable.size(), validTypes);
    }

    /**
     * 懒加载 ChatClient：识别超时 30s（参照标题服务；识别失败兜底 intent-other，不值得长等，
     * 与原 HttpClient timeout 一致）。构造器内构建逻辑迁移至此，统一 @RequiredArgsConstructor 注入。
     */
    private volatile ChatClient chatClient;

    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .timeout(Duration.ofSeconds(30)))
                            .build();
                }
            }
        }
        return chatClient;
    }

    /**
     * 仅规则前置匹配（不调 LLM）：返回首个命中的意图类型；未命中返回 null。
     * 供上下文意图延续用（对历史消息做轻量规则判定，避免递归调 LLM）。
     */
    @Override
    public String ruleMatch(String text) {
        if (text == null || text.isBlank()) return null;
        String q = text.trim();
        for (Map.Entry<String, Pattern> rule : ruleTable) {
            if (rule.getValue().matcher(q).find()) {
                return rule.getKey();
            }
        }
        return null;
    }

    /**
     * 识别用户输入意图 → 结构化结果。
     * 解析顺序：JSON {type, confidence} → 兼容裸标识符（无置信度按 0.5）→ 白名单校验 → 兜底。
     */
    public IntentResult recognize(String query, List<AgentMessage> recentMessages) {
        // 0) 规则前置：强关键词命中直接路由（免一次 LLM 调用，置信度 1.0）
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            for (Map.Entry<String, Pattern> rule : ruleTable) {
                if (rule.getValue().matcher(q).find()) {
                    log.info("意图识别规则命中: type={} query={}", rule.getKey(), q);
                    return IntentResult.rule(rule.getKey());
                }
            }
        }
        try {
            // 当前输入截断到 500 字：意图识别只需要线索，避免超长输入拖慢/抬高成本
            String truncated = query != null && query.length() > 500
                    ? query.substring(0, 500) : query;
            // 瞬态失败重试 1 次（LLM 调用幂等；识别失败本就兜底，多一次重试降低误判概率）
            String raw = retryTransient(() -> chatClient().prompt().options(OpenAiChatOptions.builder().model(gatewayModelService.getDefaultTextModel()))
                    .system(buildPrompt(recentMessages))
                    .user(truncated)
                    .call()
                    .content());
            if (raw != null && !raw.isBlank()) {
                // 1) JSON {type, confidence}
                java.util.regex.Matcher m = INTENT_JSON.matcher(raw);
                if (m.find()) {
                    String type = cleanType(m.group(1));
                    double conf = 0.5;
                    try {
                        conf = Double.parseDouble(m.group(2));
                    } catch (NumberFormatException ignore) {
                        // confidence 非法 → 0.5（视为不明确，交由阈值判断）
                    }
                    if (validTypes.contains(type)) {
                        return IntentResult.llm(type, conf);
                    }
                }
                // 2) 兼容裸标识符（LLM 未按 JSON 输出时）
                String type = cleanType(raw);
                if (validTypes.contains(type)) {
                    return IntentResult.llm(type, 0.5);
                }
                log.warn("意图识别结果不在白名单，兜底 intent-other: raw={}", raw);
            }
            return IntentResult.fallback();
        } catch (Exception e) {
            // 识别失败不阻塞对话：兜底走引导分支
            log.warn("意图识别失败，兜底 intent-other: error={}", e.getMessage());
            return IntentResult.fallback();
        }
    }

    /** 拼接 system prompt：基础规则 + 历史对话段（支撑"继续/接着上次"判断） */
    private String buildPrompt(List<AgentMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return promptConfig.get("services/intent-recognition");
        }
        StringBuilder sb = new StringBuilder(promptConfig.get("services/intent-recognition"));
        sb.append("\n\n## 历史对话（供\"继续/接着上次\"判断）\n");
        int total = 0;
        for (AgentMessage m : recentMessages) { // 时间升序：从最旧到最新
            String c = m.getContent();
            if (c == null) continue;
            if (c.length() > 100) c = c.substring(0, 100);
            if (total + c.length() > 800) break; // 总历史 800 字上限，超出丢更旧
            sb.append("user".equals(m.getRole()) ? "user: " : "assistant: ").append(c).append('\n');
            total += c.length();
        }
        return sb.toString();
    }

    /** 清洗模型输出：trim + 剥成对引号；非法时原样返回（由调用方白名单兜底） */
    String cleanType(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    /**
     * 瞬态失败重试 1 次（500ms backoff）。非瞬态异常直接抛，由调用方兜底。
     */
    private static <T> T retryTransient(java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (RuntimeException e) {
            if (!isTransient(e)) throw e;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return fn.get();
        }
    }

    /** 瞬态判定：HTTP 429/5xx 或网络超时/连接失败 */
    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
