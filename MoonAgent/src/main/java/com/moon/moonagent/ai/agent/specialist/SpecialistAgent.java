package com.moon.moonagent.ai.agent.specialist;

/**
 * 专家 Agent 接口——每个专家拥有独立 ChatClient + 工具集 + 系统提示词（来自 SKILL.md）。
 *
 * <p>PlanGraph 调用 {@link #execute}，返回 {@link SpecialistResult}：
 * <ul>
 *   <li>{@code done(message)} → 本轮完成，消息落库</li>
 *   <li>{@code handoff(target, reason, state)} → 移交给另一个专家继续</li>
 * </ul>
 */
public interface SpecialistAgent {

    /** 本专家负责的意图标识（intent-aisplit / intent-pic / intent-video / ...） */
    String intentType();

    /**
     * 自主执行：ChatClient 带工具推理 → 返回结果。
     *
     * @return 完成或移交
     */
    SpecialistResult execute(SpecialistContext context);
}
