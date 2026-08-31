package com.moon.moonagent.ai.agent.skill;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能管理器 —— 实现动态技能注册/注销/查询。
 *
 * <p>结合静态技能（从 SKILL.md 加载）和动态技能（运行时注册），
 * 提供统一的技能管理接口。
 */
@Component
@RequiredArgsConstructor
public class SkillManager implements SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final SkillLoader skillLoader;

    /** 动态注册的技能 */
    private final Map<String, AgentSkill> dynamicSkills = new ConcurrentHashMap<>();

    /** 动态技能的来源标记 */
    private final Map<String, String> dynamicSources = new ConcurrentHashMap<>();

    @Override
    public boolean register(AgentSkill skill) {
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            log.warn("[SkillManager] 尝试注册无效技能: {}", skill);
            return false;
        }

        String name = skill.name();
        
        // 检查是否与静态技能冲突
        if (skillLoader.get(name) != null) {
            log.warn("[SkillManager] 技能 '{}' 已存在于静态技能中，无法覆盖", name);
            return false;
        }

        // 检查是否与动态技能冲突
        if (dynamicSkills.containsKey(name)) {
            log.warn("[SkillManager] 动态技能 '{}' 已存在，将被覆盖", name);
        }

        dynamicSkills.put(name, skill);
        dynamicSources.put(name, "dynamic");
        log.info("[SkillManager] 注册动态技能: name={} intent={} priority={}", name, skill.intentType(), skill.priority());
        return true;
    }

    @Override
    public boolean unregister(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        // 检查是否为静态技能
        if (skillLoader.get(name) != null) {
            log.warn("[SkillManager] 无法注销静态技能: {}", name);
            return false;
        }

        AgentSkill removed = dynamicSkills.remove(name);
        if (removed != null) {
            dynamicSources.remove(name);
            log.info("[SkillManager] 注销动态技能: {}", name);
            return true;
        }

        log.warn("[SkillManager] 尝试注销不存在的技能: {}", name);
        return false;
    }

    @Override
    public List<AgentSkill> all() {
        List<AgentSkill> allSkills = new ArrayList<>(skillLoader.all());
        allSkills.addAll(dynamicSkills.values());
        return allSkills;
    }

    @Override
    public Optional<AgentSkill> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        // 先查动态技能
        AgentSkill dynamic = dynamicSkills.get(name);
        if (dynamic != null) {
            return Optional.of(dynamic);
        }

        // 再查静态技能
        AgentSkill staticSkill = skillLoader.get(name);
        return Optional.ofNullable(staticSkill);
    }

    @Override
    public Optional<AgentSkill> byIntent(String intentType) {
        if (intentType == null || intentType.isBlank()) {
            return Optional.empty();
        }

        // 先查动态技能
        for (AgentSkill skill : dynamicSkills.values()) {
            if (intentType.equals(skill.intentType())) {
                return Optional.of(skill);
            }
        }

        // 再查静态技能
        AgentSkill staticSkill = skillLoader.byIntent(intentType);
        return Optional.ofNullable(staticSkill);
    }

    @Override
    public boolean isDynamic(String name) {
        return dynamicSkills.containsKey(name);
    }

    @Override
    public List<AgentSkill> dynamicSkills() {
        return new ArrayList<>(dynamicSkills.values());
    }

    /**
     * 获取所有技能（静态 + 动态），按 priority 升序排列。
     *
     * @return 排序后的技能列表
     */
    public List<AgentSkill> allOrdered() {
        List<AgentSkill> allSkills = all();
        allSkills.sort(Comparator.comparingInt(AgentSkill::priority));
        return allSkills;
    }

    /**
     * 获取所有技能（静态 + 动态），按 menuOrder 升序排列（用于澄清菜单）。
     *
     * @return 排序后的技能列表
     */
    public List<AgentSkill> allMenuOrdered() {
        List<AgentSkill> allSkills = all();
        allSkills.sort(Comparator.comparingInt(AgentSkill::menuOrder));
        return allSkills;
    }

    /**
     * 根据工具名称查找相关技能。
     *
     * @param toolName 工具名称
     * @return 使用该工具的技能列表
     */
    public List<AgentSkill> findByTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }

        List<AgentSkill> result = new ArrayList<>();
        for (AgentSkill skill : all()) {
            if (skill.tools() != null && skill.tools().contains(toolName)) {
                result.add(skill);
            }
        }
        return result;
    }

    /**
     * 根据触发词查找匹配的技能。
     *
     * @param content 用户输入内容
     * @return 匹配的技能列表（按 priority 升序）
     */
    public List<AgentSkill> findByTriggers(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<AgentSkill> result = new ArrayList<>();
        for (AgentSkill skill : allOrdered()) {
            if (skill.triggers() != null) {
                for (String trigger : skill.triggers()) {
                    if (content.matches(".*" + trigger + ".*")) {
                        result.add(skill);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 创建一个新的动态技能。
     *
     * @param name 技能名称
     * @param description 技能描述
     * @param label 显示标签
     * @param modelRole 模型角色
     * @param tools 工具列表
     * @param triggers 触发词列表
     * @param priority 优先级
     * @param menuOrder 菜单顺序
     * @param hitlManifest HITL 按钮清单
     * @return 创建的技能
     */
    public AgentSkill createDynamicSkill(
            String name,
            String description,
            String label,
            String modelRole,
            List<String> tools,
            List<String> triggers,
            int priority,
            int menuOrder,
            List<Map<String, Object>> hitlManifest) {
        
        AgentSkill skill = new AgentSkill(
                "intent-" + name,
                name,
                description,
                label,
                modelRole != null ? modelRole : "text",
                tools != null ? tools : List.of(),
                Map.of(),
                triggers != null ? triggers : List.of(),
                priority,
                menuOrder,
                hitlManifest != null ? hitlManifest : List.of(),
                Map.of(),
                List.of()
        );

        register(skill);
        return skill;
    }
}
