package com.moon.moonagent.ai.agent;

import com.moon.moonagent.entity.AgentMessage;

import java.util.List;

/**
 * 意图识别服务：把意图识别提取到后端。
 *
 * 职责：用户发消息时，后端调 LLM 网关识别用户输入意图 → 返回 type（intent-aisplit /
 * intent-pic / intent-video / intent-other），随请求的 inputs 传给
 * start 节点 type 变量，「意图路由」if-else 直接按 type 分流（工作流侧已删除「意图识别」LLM 节点）。
 *
 * 历史上下文：最多携带最近 {@link #HISTORY_LIMIT} 条消息，支撑"继续/接着上次"判断。
 *
 * <p>实现：{@link com.moon.moonagent.ai.agent.impl.IntentRecognitionServiceImpl}。
 */
public interface IntentRecognitionService {

    /** 历史上下文：最多取最近 8 条消息（支撑"继续/接着上次"判断） */
    int HISTORY_LIMIT = 8;

    /** 兜底意图：识别失败/解析失败/白名单外 → 引导分支，不阻塞对话 */
    String FALLBACK_TYPE = "intent-other";

    /**
     * 识别用户输入意图 → 结构化结果（type + confidence + source）。
     *
     * @param query          用户当前输入（≤500 字截断）
     * @param recentMessages 最近对话历史（时间升序；可为空）
     * @return 意图识别结果；任何失败返回 {@link #FALLBACK_TYPE} + 置信度 0
     */
    IntentResult recognize(String query, List<AgentMessage> recentMessages);

    /** 识别意图并把本轮网关调用记入执行段指标；旧调用方可继续使用两参数重载。 */
    IntentResult recognize(String query, List<AgentMessage> recentMessages,
                           com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace);

    /**
     * 仅规则前置匹配（不调 LLM）：返回首个命中的意图类型；未命中返回 null。
     * 供上下文意图延续用（对历史消息做轻量规则判定，避免递归调 LLM）。
     */
    String ruleMatch(String text);
}
