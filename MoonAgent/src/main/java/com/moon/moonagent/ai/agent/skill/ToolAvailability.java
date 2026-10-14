package com.moon.moonagent.ai.agent.skill;

/**
 * 「某个工具当前是否已注册」的只读查询契约。
 *
 * <p>工具注册表会随 Skill 激活、MCP 延迟连接与配置开关变化，因此系统提示词的能力声明
 * 必须按轮次查询实时状态，而不是静态列举。抽出本接口后，能力上报逻辑无需启动
 * Spring 上下文即可测试。
 */
public interface ToolAvailability {

    /** 工具是否已注册（可由本地技能工具或延迟连接的 MCP 工具提供）。 */
    boolean hasTool(String name);
}
