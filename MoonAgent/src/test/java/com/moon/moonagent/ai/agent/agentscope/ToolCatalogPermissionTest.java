package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentApprovalMode;
import io.agentscope.core.permission.PermissionContextState;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * catalog 里声明为只读的工具必须在默认审批模式下自动放行，
 * 不能依赖 {@code permissionContext} 里那张手工名单（否则新增只读工具会被误拦）。
 */
class ToolCatalogPermissionTest {

    @Test
    void catalogReadOnlyToolsAreAllowedWithoutToolkitReadOnlySet() {
        PermissionContextState permission = MoonAgentRuntime.permissionContext(
                AgentApprovalMode.PLAN_APPROVAL, Set.of(), Set.of());

        Set<String> catalogReadOnly = ToolCatalog.specs().stream()
                .filter(spec -> spec.policy() != null && spec.policy().readOnly())
                .map(ToolCatalog.Spec::name)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(catalogReadOnly).isNotEmpty();
        assertThat(permission.getAllowRules())
                .as("catalog 只读工具必须自动放行: %s", catalogReadOnly)
                .containsKeys(catalogReadOnly.toArray(String[]::new));
    }

    @Test
    void catalogWriteToolsAreNotAutoAllowedByTheReadOnlyRule() {
        PermissionContextState permission = MoonAgentRuntime.permissionContext(
                AgentApprovalMode.PLAN_APPROVAL, Set.of(), Set.of());

        assertThat(permission.getAllowRules())
                .doesNotContainKeys(
                        AgentScopeImageTools.GENERATE_IMAGE,
                        AgentScopeImageTools.GENERATE_IMAGE_BATCH,
                        AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD);
    }
}
