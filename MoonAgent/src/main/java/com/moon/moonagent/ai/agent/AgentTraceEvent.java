package com.moon.moonagent.ai.agent;

import java.time.OffsetDateTime;

/**
 * 编排轨迹事件：每个阶段（意图识别 / specialist 执行 / handoff / 路由变更 / HITL 暂停 / 工具调用）一条。
 * 通过 SSE {@code agent_trace} 事件下发，也随 turn metadata 落库。
 *
 * @param id             事件 id（UUID 字符串）
 * @param phase          阶段（intent_recognize / specialist_execute / handoff / route_change / hitl / tool_call ...）
 * @param agent          Moon 主代理（固定 "moon"）
 * @param specialist     工具或子智能体标签（无则空，字段名保留前端兼容）
 * @param status         node_started | node_completed | route_changed | ...
 * @param title          可读标题（如「意图识别」「生成分镜」）
 * @param summary        摘要（可用 LLM 转中文；不暴露原始 prompt）
 * @param currentIntent  当前意图
 * @param targetIntent   目标意图（切换后的意图；无则空）
 * @param startedAt      开始时间
 * @param durationMs     耗时（毫秒）
 */
public record AgentTraceEvent(
    String id,
    String phase,
    String agent,
    String specialist,
    String status,
    String title,
    String summary,
    String currentIntent,
    String targetIntent,
    OffsetDateTime startedAt,
    Long durationMs
) {}
