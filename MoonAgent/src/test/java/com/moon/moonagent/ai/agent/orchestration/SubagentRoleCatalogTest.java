package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentRoleCatalogTest {

    private final SubagentRoleCatalog catalog = new SubagentRoleCatalog(new ObjectMapper());

    @Test
    void loadsTheFourLocalRoles() {
        assertThat(catalog.names())
                .containsExactlyInAnyOrder(
                        "researcher", "screenwriter", "visual-director", "reviewer", "writer");
        assertThat(catalog.require("researcher").tools())
                .contains("get_creative_context", "list_project_storyboard");
        assertThat(catalog.require("researcher").maxTurns()).isEqualTo(6);
    }

    @Test
    void dynamicRoleOverridesBuiltInAndCanFallBack() {
        SubagentRole dynamic = new SubagentRole(
                "researcher", "动态调研", "只查项目记忆", java.util.List.of("recall_memory"), 4);
        catalog.refresh(java.util.List.of(dynamic));

        assertThat(catalog.require("researcher").prompt()).isEqualTo("只查项目记忆");
        assertThat(catalog.require("researcher").maxTurns()).isEqualTo(4);

        catalog.refresh(java.util.List.of());
        assertThat(catalog.require("researcher").prompt()).contains("只读调研");
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> catalog.require("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记");
    }
}
