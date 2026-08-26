package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

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

    /** resume 阶段用户提交的生成参数选择（如 {model, resolution, duration}；空 Map=未提交，由 Orchestrator.resume 设置） */
    private Map<String, String> params = Map.of();

    /** resume 阶段用户勾选的资产 ID 列表（资产选择卡片提交；空=未勾选/未涉及资产，由 Orchestrator.resume 设置） */
    private java.util.List<String> assetIds = java.util.List.of();

    /** 当前意图类型（如 intent-aisplit；由 Orchestrator 分发/续流时设置，供 Skill 按需取提示词） */
    private String intentType = "";

    /**
     * resume 阶段 handler 声明的移交目标 intentType（非空=本环节已完成、请交给下一专家）。
     * per-request 可变状态，修复多会话并发互踩；由 Specialist 读后转 {@code SpecialistResult.handoff}。
     */
    private String handoffTarget = "";

    /** 移交附带的下游状态（如 projectId / sceneNums）；与 {@link #handoffTarget} 一起被下一专家消费 */
    private Map<String, Object> handoffState = Map.of();
}
