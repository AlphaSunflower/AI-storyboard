package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill ↔ Handler 一致性校验（启动 fail-fast，防漂移）。
 *
 * <p>模块化约定：一个能力 = 一个 {@code SKILL.md} + 一个 {@code IntentHandler}。
 * 若只加了 handler 忘了写 skill（或反之），路由推导（规则前置表/澄清菜单）会与 handler 脱节，
 * 此处启动即报错，避免「能力声明了却没实现/没知识」的静默故障。
 *
 * <p>校验项：
 * <ol>
 *   <li>每个 {@link IntentHandler#intentType()} 都有对应 SKILL.md（reverse 同理——每个 skill 都有 handler）；</li>
 *   <li>有 handler 的能力必须声明 triggers / label；无 handler 能力必须声明 triggers / label / model_role，hitl 可选；</li>
 *   <li>声明的 tools 必须能被 {@link SkillExpander} 解析（@Tool/@McpTool 存在）；</li>
 *   <li>无 handler 且声明 flow 的技能，每个 flow 步骤在 body 里都有对应的 {@code ### stepId} 段。</li>
 * </ol>
 */
@RequiredArgsConstructor
public class SkillConsistencyValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillConsistencyValidator.class);

    private final List<IntentHandler> handlers;
    private final SkillManager skillManager;
    private final SkillExpander skillExpander;

    @PostConstruct
    public void validate() {
        Set<String> handledTypes = new HashSet<>();
        for (IntentHandler h : handlers) {
            String type = h.intentType();
            handledTypes.add(type);
            AgentSkill s = skillManager.byIntent(type).orElse(null);
            if (s == null) {
                throw fail("IntentHandler {} 缺少对应 SKILL.md（intentType={}）", h.getClass().getSimpleName(), type);
            }
            if (!IntentRecognitionService.FALLBACK_TYPE.equals(type)) {
                if (s.triggers().isEmpty()) {
                    throw fail("SKILL.md {} 缺少 triggers（intentType={}）——规则前置无法识别", s.name(), type);
                }
                if (s.label() == null || s.label().isBlank()) {
                    throw fail("SKILL.md {} 缺少 label（intentType={}）——澄清菜单无文案", s.name(), type);
                }
            }
            validateTools(s);
        }
        // 允许无 handler 技能由通用执行器接管：仅校验路由与执行必需字段；hitl 可选（空=直接执行）
        int handlerLess = 0;
        for (AgentSkill s : skillManager.all()) {
            if (handledTypes.contains(s.intentType())) continue;
            handlerLess++;
            if (s.triggers().isEmpty()) {
                throw fail("SKILL.md {} 声明了能力 {}，但无 handler 且缺少 triggers——规则前置无法识别",
                        s.name(), s.intentType());
            }
            if (s.label() == null || s.label().isBlank()) {
                throw fail("SKILL.md {} 声明了能力 {}，但无 handler 且缺少 label——澄清菜单无文案",
                        s.name(), s.intentType());
            }
            if (s.modelRole() == null || s.modelRole().isBlank()) {
                throw fail("SKILL.md {} 声明了能力 {}，但无 handler 且缺少 model_role——通用执行器无法选模型",
                        s.name(), s.intentType());
            }
            if (s.flow() != null && !s.flow().isEmpty()) {
                for (String stepId : s.flow()) {
                    if (!s.nodePrompts().containsKey(stepId)) {
                        throw fail("SKILL.md {} 声明了 flow 步骤 {}，但 body 缺少 ### {} 段",
                                s.name(), stepId, stepId);
                    }
                }
            }
            validateTools(s);
        }
        log.info("Skill 一致性校验通过：{} 个 Handler 对应 SKILL.md；另有 {} 个无 handler 技能走通用执行器",
                handledTypes.size(), handlerLess);
    }

    /** 本地工具必须能被 SkillExpander 解析；远程 MCP 工具允许在延迟连接完成前缺失。 */
    private void validateTools(AgentSkill s) {
        if (s.tools() == null || s.tools().isEmpty()) return;
        int resolved = skillExpander.toolCallbacks(s.tools()).length;
        if (resolved != s.tools().size()) {
            Set<String> available = skillExpander.allToolNames();
            List<String> missing = s.tools().stream().filter(tool -> !available.contains(tool)).toList();
            boolean deferredRemoteTools = !missing.isEmpty() && missing.stream().allMatch(this::isDeferredRemoteTool);
            if (deferredRemoteTools) {
                log.warn("SKILL.md {} 声明的远程 MCP 工具尚未连接，将在调用前检查: {}", s.name(), missing);
                return;
            }
            throw fail("SKILL.md {} 声明了工具 {}，但有 {} 项无法解析（@Tool/@McpTool 缺失）",
                    s.name(), s.tools(), s.tools().size() - resolved);
        }
    }

    private boolean isDeferredRemoteTool(String tool) {
        return tool != null && (tool.startsWith("canvas_")
                || "generate_image".equals(tool)
                || "generate_video".equals(tool));
    }

    private IllegalStateException fail(String fmt, Object... args) {
        String msg = "Skill 一致性校验失败：" + String.format(fmt, args);
        log.error(msg);
        return new IllegalStateException(msg);
    }
}
