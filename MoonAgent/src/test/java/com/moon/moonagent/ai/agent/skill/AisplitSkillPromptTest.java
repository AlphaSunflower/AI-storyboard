package com.moon.moonagent.ai.agent.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AisplitSkillPromptTest {

    private static AgentSkill aisplit;

    @BeforeAll
    static void loadSkills() {
        SkillLoader loader = new SkillLoader();
        loader.loadAll();
        aisplit = loader.get("aisplit");
    }

    @Test
    void storyboardPlanTreatsMissingMaterialsAsValidInput() {
        assertThat(aisplit).isNotNull();

        String prompt = aisplit.nodePrompts().get("storyboard-plan");

        assertThat(prompt)
                .contains("没有素材绑定是合法输入")
                .contains("不得仅因未提供素材绑定而追问")
                .contains("无素材时 referenceBindings=[]")
                .contains("有完整可理解的剧本时直接 type=1");
    }
}
