package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentConversation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 一轮编排的输入上下文：会话 + 消息 + 参考图 + SSE 输出。
 *
 * <p>{@code lastMessage} 为每请求可变状态：记录本轮最后一条 message 事件的内容
 * （流式增量时逐段更新），供 run 返回后调用方落库 assistant 消息。
 * per-request 实例，天然并发安全（替代原 Orchestrator 实例字段，修复多会话并发互踩）。
 */
@Getter
@Setter
@RequiredArgsConstructor
public class OrchestrationRequest {

    private final AgentConversation conversation;
    private final String content;
    private final String picUrl;
    private final SseEmitter emitter;

    /** 本轮最后一条 message 事件内容（流式增量时逐段更新；空串=尚未发出） */
    private String lastMessage = "";

    /** resume 阶段用户点选的选项 id（run 阶段为空串；由 Orchestrator.resume 设置） */
    private String action = "";

    /** resume 阶段自定义输入文本（action=custom 时携带，其余为空串；由 Orchestrator.resume 设置） */
    private String customText = "";
}
