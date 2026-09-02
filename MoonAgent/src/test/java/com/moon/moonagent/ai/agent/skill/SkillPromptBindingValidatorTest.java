package com.moon.moonagent.ai.agent.skill;

import com.moon.moonagent.ai.agent.SkillPromptBindingValidator;
import com.moon.moonagent.config.PromptConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能 gate 提示词绑定校验 —— 启动期防「既不在 SKILL body、又没有 YAML 兜底」的空提示词。
 *
 * <p>正例：用真实 {@link SkillLoader} 加载 classpath:skills，mock PromptConfig 认为所有 gate fallback
 * key 已从 YAML 删除，校验应通过（真实 SKILL body 都含对应 {@code ### stepId} 段）。
 * 负例：某技能缺一个 gate 步骤且无兜底，应 fail-fast。
 */
class SkillPromptBindingValidatorTest {

    private SkillManager skillManager;
    private PromptConfig promptConfig;

    @BeforeEach
    void setup() {
        SkillLoader skillLoader = new SkillLoader();
        skillLoader.loadAll();
        skillManager = new SkillManager(skillLoader);
        promptConfig = mock(PromptConfig.class);
        // 现状：演进到 skill-first 后，这些 gate 的 fallback 已从 prompts/** 删除
        when(promptConfig.contains(anyString())).thenReturn(false);
    }

    @Test
    void passesWhenRealSkillsContainGateSteps() {
        assertDoesNotThrow(() -> new SkillPromptBindingValidator(skillManager, promptConfig).validate());
    }

    @Test
    void failsWhenSkillMissingGateStepAndNoFallback() {
        SkillManager broken = mock(SkillManager.class);
        AgentSkill aisplitMissingStep = AgentSkill.of("aisplit",
                Map.of("name", "aisplit", "description", "d", "label", "l", "model_role", "text"),
                Map.of("script-optimize", "x", "requirement-clarify", "x", "scene-params-recommend", "x"));
        when(broken.byIntent("intent-aisplit")).thenReturn(Optional.of(aisplitMissingStep));

        assertThrows(IllegalStateException.class,
                () -> new SkillPromptBindingValidator(broken, promptConfig).validate());
    }
}
