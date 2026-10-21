package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    @Test
    void everySpecHasLabelAndOnlyKnownGroups() {
        Map<String, ToolCatalog.Group> groups = ToolCatalog.groups();
        for (ToolCatalog.Spec spec : ToolCatalog.specs()) {
            assertThat(spec.name()).isNotBlank();
            assertThat(spec.label()).as("label of %s", spec.name()).isNotBlank();
            for (String group : spec.groups()) {
                assertThat(groups).as("group %s of %s", group, spec.name()).containsKey(group);
                assertThat(groups.get(group).skill()).isNotBlank();
            }
        }
    }

    @Test
    void everyGroupIsDeclaredOnceWithASkill() {
        assertThat(ToolCatalog.groups().values())
                .extracting(ToolCatalog.Group::name)
                .doesNotHaveDuplicates();
        assertThat(ToolCatalog.groups().values())
                .allSatisfy(group -> {
                    assertThat(group.description()).isNotBlank();
                    assertThat(group.skill()).isNotBlank();
                    assertThat(group.activeByDefault()).isFalse();
                });
    }

    @Test
    void canvasProfileExcludesStoryboardButKeepsEverythingElse() {
        ToolCatalog.Profile normal = ToolCatalog.profileFor(false);
        ToolCatalog.Profile canvas = ToolCatalog.profileFor(true);

        assertThat(normal.skills()).contains("storyboard", "image", "video", "canvas-design");
        assertThat(normal.groups()).contains(ToolCatalog.STORYBOARD_TOOLS);

        assertThat(canvas.skills()).doesNotContain("storyboard");
        assertThat(canvas.skills()).contains("image", "video", "canvas-design", "canvas-run");
        assertThat(canvas.groups()).doesNotContain(ToolCatalog.STORYBOARD_TOOLS);
        assertThat(canvas.groups()).contains(ToolCatalog.IMAGE_TOOLS, ToolCatalog.VIDEO_TOOLS);
    }

    @Test
    void optionalSkillsOnlyAppearWhenReferenced() {
        assertThat(ToolCatalog.profileFor(false).skills())
                .as("ppt-master 默认不挂载")
                .doesNotContain("ppt-master");
        assertThat(ToolCatalog.profileFor(false, Set.of("ppt-master")).skills())
                .contains("ppt-master");
        assertThat(ToolCatalog.profileFor(false, Set.of("unknown-skill")).skills())
                .doesNotContain("unknown-skill");
        assertThat(ToolCatalog.profileFor(true, Set.of("ppt-master")).skills())
                .as("画布编辑回合不叠加可选技能")
                .doesNotContain("ppt-master");
    }

    @Test
    void explicitPoliciesSurviveTheCatalogMove() {
        ToolPolicy image = ToolCatalog.policy(AgentScopeImageTools.GENERATE_IMAGE).orElseThrow();
        assertThat(image.toolKind()).isEqualTo("MEDIA_IMAGE");
        assertThat(image.actionType()).isEqualTo("media-image");
        assertThat(image.preflight()).isTrue();

        ToolPolicy storyboardDelete = ToolCatalog
                .policy(AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT)
                .orElseThrow();
        assertThat(storyboardDelete.toolKind()).isEqualTo("STORYBOARD_DELETE");
        assertThat(storyboardDelete.actionType()).isEqualTo("storyboard-project-delete");

        assertThat(ToolCatalog.policy(AgentScopeDocumentTools.READ_DOCUMENT))
                .as("常驻读取类工具沿用工具自身 readOnly 推导，不在目录里重复声明")
                .isEmpty();
    }

    @Test
    void specsHaveNoDuplicateNames() {
        List<String> names = ToolCatalog.specs().stream().map(ToolCatalog.Spec::name).toList();
        assertThat(names).doesNotHaveDuplicates();
    }
}
