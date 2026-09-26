package com.moon.moonagent.ai.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageTest {

    private SkillManager skillManager;

    @BeforeEach
    void setUp() {
        SkillLoader loader = new SkillLoader();
        loader.loadAll();
        skillManager = new SkillManager(loader);
    }

    @Test
    void loadsOnlyTheSixProductionSkills() {
        Set<String> names = skillManager.all().stream()
                .map(AgentSkill::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "storyboard", "image", "video", "canvas-design", "canvas-run", "general");
    }

    @Test
    void readsMoonMetadataAndStandardAllowedTools() {
        AgentSkill image = skillManager.get("image").orElseThrow();

        assertThat(image.label()).isEqualTo("生成图片");
        assertThat(image.modelRole()).isEqualTo("image");
        assertThat(image.tools()).contains("generate_image", "generate_image_batch");
    }
}

