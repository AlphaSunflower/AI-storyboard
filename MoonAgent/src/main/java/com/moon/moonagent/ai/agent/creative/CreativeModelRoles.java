package com.moon.moonagent.ai.agent.creative;

/**
 * 角色到模型名的解析契约（只读）。
 *
 * <p>抽出接口的目的：让系统提示词的动态注入块（能力与子智能体声明）可以在不启动
 * Spring 上下文、不依赖具体网关客户端的前提下被测试。
 */
public interface CreativeModelRoles {

    /** 解析角色对应模型名；无法解析时回退到网关默认模型。 */
    String resolve(String role);

    /** 解析角色对应模型名；无法解析时返回 {@code null}。 */
    String resolveOrNull(String role);
}
