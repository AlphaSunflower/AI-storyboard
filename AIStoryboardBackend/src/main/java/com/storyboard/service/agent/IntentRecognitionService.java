package com.storyboard.service.agent;

import com.storyboard.entity.AgentMessage;

import java.util.List;

/**
 * 意图识别服务：把意图识别从 Dify 工作流提取到后端。
 *
 * 职责：用户发消息时，后端调 LLM 网关识别用户输入意图 → 返回 type（intent-aisplit /
 * intent-pic / intent-video / intent-other），随 chat-messages 请求的 inputs 传给 Dify
 * start 节点 type 变量，「意图路由」if-else 直接按 type 分流（Dify 侧已删除「意图识别」LLM 节点）。
 *
 * 历史上下文：最多携带最近 {@link #HISTORY_LIMIT} 条消息，支撑"继续/接着上次"判断。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.IntentRecognitionServiceImpl}。
 */
public interface IntentRecognitionService {

    /** 历史上下文：最多取最近 8 条消息（支撑"继续/接着上次"判断） */
    int HISTORY_LIMIT = 8;

    /** 兜底意图：识别失败/解析失败/白名单外 → 引导分支，不阻塞对话 */
    String FALLBACK_TYPE = "intent-other";

    /**
     * 识别用户输入意图 → type。
     *
     * @param query          用户当前输入（≤500 字截断）
     * @param recentMessages 最近对话历史（时间升序；可为空）
     * @return 四类意图之一；任何失败返回 {@link #FALLBACK_TYPE}
     */
    String recognize(String query, List<AgentMessage> recentMessages);
}
