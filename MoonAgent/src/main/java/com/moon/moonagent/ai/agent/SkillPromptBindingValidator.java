package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.config.PromptConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用户面技能 gate 提示词绑定校验（启动 fail-fast，防「既不在 SKILL body 又没有 YAML 兜底」的空白提示词）。
 *
 * <p>边界（skills-vs-prompts）：用户面技能的编排 gate 提示词以 SKILL.md body 的 {@code ### stepId} 段为单一来源；
 * {@code prompts/**} 仅保留内部子服务提示词（{@code services/*}、{@code script/*}）与跨切面模板
 * （{@code orchestrator/hitl-card}、{@code friendly-error}、{@code image-recovery}）。
 *
 * <p>规则：对 {@link AgentOrchestratorSupport#gatePrompts()} 每个绑定——
 * 若 fallbackKey 不在 PromptConfig（YAML 已删除/未迁移），则 stepId 必须存在于对应 SKILL body（否则空提示词）；
 * 若 fallbackKey 存在于 YAML，则为迁移期安全网，允许缺 stepId。
 */
@RequiredArgsConstructor
public class SkillPromptBindingValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillPromptBindingValidator.class);

    private final SkillManager skillManager;
    private final PromptConfig promptConfig;

    @PostConstruct
    public void validate() {
        List<AgentOrchestratorSupport.GatePrompt> bindings = AgentOrchestratorSupport.gatePrompts();
        for (AgentOrchestratorSupport.GatePrompt b : bindings) {
            AgentSkill s = skillManager.byIntent(b.intentType()).orElse(null);
            if (s == null) {
                throw fail("gate 绑定 {} 指向不存在的意图 {}（缺对应 SKILL.md）", b, b.intentType());
            }
            boolean fallbackExists = promptConfig.contains(b.fallbackKey());
            if (!fallbackExists && !s.nodePrompts().containsKey(b.stepId())) {
                throw fail("gate 绑定 {} 的 fallbackKey={} 不在 prompts/**，但对应 SKILL.md [{}] body 缺 ### {} 段",
                        b, b.fallbackKey(), s.name(), b.stepId());
            }
        }
        log.info("技能 gate 提示词绑定校验通过：{} 条（fallback 存在则允许缺 skill 段）", bindings.size());
    }

    private IllegalStateException fail(String fmt, Object... args) {
        String msg = "Skill 提示词绑定校验失败：" + String.format(fmt, args);
        log.error(msg);
        return new IllegalStateException(msg);
    }
}
