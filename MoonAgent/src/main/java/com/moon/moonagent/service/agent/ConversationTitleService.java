package com.moon.moonagent.service.agent;

/**
 * 会话标题异步生成服务。
 *
 * 职责：新会话首条消息发出后，用大模型（不思考模式）根据首条用户消息生成简短标题，
 * 并条件更新 conversations.title（仅当标题仍为默认值「新对话」时，避免覆盖用户手动重命名）。
 *
 * 实现说明：已从手写 JDK HttpClient 直连网关 /v1/chat/completions 改为
 * Spring AI ChatClient（spring.ai.openai.base-url 已指向网关 /v1，纯文本调用，无结构化输出）。
 *
 * <p>实现：{@link com.moon.moonagent.service.agent.impl.ConversationTitleServiceImpl}。
 */
public interface ConversationTitleService {

    /**
     * 首条消息异步重命名：生成标题 → 条件更新。
     * 任何失败仅记日志，绝不抛出（异步线程异常不影响对话主流程）。
     */
    void renameOnFirstMessage(String conversationId, String firstUserContent);
}
