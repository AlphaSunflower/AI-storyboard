package com.moon.moonagent.ai.agent.agentscope.workflow;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeStructuredModels;
import com.moon.moonagent.ai.agent.agentscope.StructuredDecisionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowIntentResolverTest {

    @Test
    void explicitSerialStageProtocolWinsWithoutModelDecision() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        when(decisions.resolveWorkflow(anyString(), anyString()))
                .thenReturn(new AgentScopeStructuredModels.WorkflowDecision(
                        "", false, false, false, List.of(), List.of(), 0, "fallback"));
        AgentWorkflowIntentResolver resolver = new AgentWorkflowIntentResolver(decisions);

        AgentWorkflowModels.Resolution resolution = resolver.resolve("""
                必须串行执行，不能并行，不能跳阶段。
                阶段1：生成6个镜头分镜表。输出【阶段1完成】
                阶段2：为每个镜头生成首尾帧提示词。输出【阶段2完成】
                阶段3：依次生成图片。输出【阶段3完成】
                阶段4：生成完整30秒视频。输出【阶段4完成】
                """);

        assertThat(resolution.mode())
                .isEqualTo(AgentWorkflowModels.Mode.USER_DEFINED_WORKFLOW);
        assertThat(resolution.constraint().serial()).isTrue();
        assertThat(resolution.constraint().noSkip()).isTrue();
        assertThat(resolution.steps()).hasSize(4);
        assertThat(resolution.steps().get(2).repeatCount()).isEqualTo(6);
        assertThat(resolution.steps().get(3).toolName()).isEqualTo("merge_storyboard_videos");
    }

    @Test
    void normalGoalUsesAutoSkill() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        when(decisions.resolveWorkflow(anyString(), anyString()))
                .thenReturn(new AgentScopeStructuredModels.WorkflowDecision(
                        "AUTO_SKILL", false, false, false, List.of(), List.of(), 1, "normal goal"));
        AgentWorkflowIntentResolver resolver = new AgentWorkflowIntentResolver(decisions);

        AgentWorkflowModels.Resolution resolution = resolver.resolve("帮我生成一支国风短片分镜");

        assertThat(resolution.mode()).isEqualTo(AgentWorkflowModels.Mode.AUTO_SKILL);
        assertThat(resolution.steps()).isEmpty();
    }
}
