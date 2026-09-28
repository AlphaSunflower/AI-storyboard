package com.moon.moonagent.ai.agent.agentscope.nacos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NacosAssetPropertiesTest {

    @Test
    void skillNamesDefaultToTheProductionSet() {
        NacosAssetProperties properties = new NacosAssetProperties();

        assertThat(properties.getSkillNames()).containsExactly(
                "image", "video", "storyboard", "canvas-design", "canvas-run", "general");
    }

    @Test
    void namespaceAndVersionTargetsAreNormalized() {
        NacosAssetProperties properties = new NacosAssetProperties();

        properties.setNamespace(" ");
        properties.setSkillVersion(" 3.0.0 ");
        properties.setSkillLabel(" stable ");

        assertThat(properties.getNamespace()).isEqualTo("public");
        assertThat(properties.getSkillVersion()).isEqualTo("3.0.0");
        assertThat(properties.getSkillLabel()).isEqualTo("stable");
    }
}
