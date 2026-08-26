package com.moon.moonagent.service;

import java.util.function.Consumer;

/**
 * 画布类智能体的"回答"能力——设计助理（Morrow 画布）与 MoonAgent 共用。
 * 复用共享会话（conversations / agent_messages）+ Spring AI ChatClient 流式回答。
 */
public interface DesignAssistantCapability {

    /**
     * 流式回答设计问题：持久化 user/assistant 消息到共享会话，逐 token 回调 {@code onDelta}，返回完整回答文本。
     * <p>注意：只回调增量子串（delta），不要回调完整消息——前端会把 delta 增量累积成一条气泡，完整消息会造成重复会话气泡。
     *
     * @param prompt          用户提问
     * @param conversationId  共享会话 id（可空，空则不落库）
     * @param onDelta         每个增量 token 的回调（可为 null）
     * @return 完整回答文本
     */
    String answer(String prompt, String conversationId, Consumer<String> onDelta);
}
