package com.moon.moonagent.ai.agent.skill;

import java.util.List;
import java.util.Optional;

/**
 * 动态技能注册表接口 —— 支持运行时注册/注销/查询技能。
 *
 * <p>设计目标：打破硬编码技能管理，允许根据工具和技能动态扩展能力。
 * 技能可以是静态的（从 SKILL.md 加载）或动态的（运行时注册）。
 */
public interface SkillRegistry {

    /**
     * 注册一个动态技能。
     *
     * @param skill 要注册的技能
     * @return true 如果成功注册，false 如果已存在同名技能
     */
    boolean register(AgentSkill skill);

    /**
     * 注销一个动态技能。
     *
     * @param name 技能名称
     * @return true 如果成功注销，false 如果不存在或为静态技能
     */
    boolean unregister(String name);

    /**
     * 获取所有技能（静态 + 动态）。
     *
     * @return 所有技能的列表
     */
    List<AgentSkill> all();

    /**
     * 按名称获取技能。
     *
     * @param name 技能名称
     * @return 技能（如果存在）
     */
    Optional<AgentSkill> get(String name);

    /**
     * 按意图类型获取技能。
     *
     * @param intentType 意图类型
     * @return 技能（如果存在）
     */
    Optional<AgentSkill> byIntent(String intentType);

    /**
     * 检查技能是否为动态注册的。
     *
     * @param name 技能名称
     * @return true 如果是动态技能
     */
    boolean isDynamic(String name);

    /**
     * 获取所有动态注册的技能。
     *
     * @return 动态技能列表
     */
    List<AgentSkill> dynamicSkills();
}
