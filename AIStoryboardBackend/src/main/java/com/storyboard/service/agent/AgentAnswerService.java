package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 主回答服务（intent-other 闲聊/引导分支）。
 *
 * 用 ChatClient 拼会话历史生成回答（等价原 Dify 应用「引导回复」，体验升级），
 * 非流式一次 message 事件（前端打字机按 message 事件逐段拼接，整段兼容；
 * 流式列为后续增强）。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.AgentAnswerServiceImpl}。
 */
public interface AgentAnswerService {

    /**
     * 生成回答文本（不发射 SSE；由编排统一发 message/message_end 并落库）。
     *
     * @param conversation 会话（已校验归属）
     * @param content      用户消息
     * @return 回答文本（LLM 失败返回降级文案）
     */
    String generate(AgentConversation conversation, String content);

    /**
     * 生成回答并推 SSE message 事件（blocking 路径兼容入口）。
     *
     * @param conversation 会话（已校验归属）
     * @param content      用户消息
     * @param emitter      SSE 输出
     * @return 回答文本（调用方可落库）
     */
    String answer(AgentConversation conversation, String content, SseEmitter emitter);
}
