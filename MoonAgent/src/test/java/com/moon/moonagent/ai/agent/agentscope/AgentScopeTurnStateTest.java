package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeTurnStateTest {

    @Test
    void synchronousTaskGraphSuppressesAsyncTaskClaimCorrection() {
        AgentScopeTurnState state = new AgentScopeTurnState();

        assertThat(state.hasSynchronousGraphCalled()).isFalse();
        state.markSynchronousGraphCalled();

        assertThat(state.hasSynchronousGraphCalled()).isTrue();
    }

    @Test
    void keepsMediaInPersistedContentWithoutCannedPreface() {
        AgentScopeTurnState state = new AgentScopeTurnState();

        state.addImage("/result.png", "生成图片");

        String content = state.compose("图片已经生成完成。");
        assertThat(content)
                .contains("![生成图片](/result.png)")
                .contains("图片已经生成完成。");
    }

    @Test
    void followUpVideoCanBePlannedOnlyOnceAndBlocksSeparateVideo() {
        AgentScopeTurnState state = new AgentScopeTurnState();

        assertThat(state.tryPlanFollowUpVideo()).isTrue();
        assertThat(state.tryPlanFollowUpVideo()).isFalse();
        assertThat(state.isFollowUpVideoPlanned()).isTrue();

        AgentScopeTurnState startedVideo = new AgentScopeTurnState();
        assertThat(startedVideo.hasStartedMedia("video")).isFalse();
        startedVideo.markMediaStarted("video");
        assertThat(startedVideo.hasStartedMedia("video")).isTrue();
        assertThat(startedVideo.tryPlanFollowUpVideo()).isFalse();
    }

    @Test
    void exposesReferenceInputModeToTurnPrompt() {
        AgentScopeTurnState state = new AgentScopeTurnState();

        state.setReferenceInputMode(AgentScopeTurnState.ReferenceInputMode.UPLOAD_ONLY);
        assertThat(state.referenceInputMode())
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.UPLOAD_ONLY);

        state.setReferenceInputMode(AgentScopeTurnState.ReferenceInputMode.ASSET_ONLY);
        assertThat(state.referenceInputMode())
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.ASSET_ONLY);

        state.setReferenceInputMode(AgentScopeTurnState.ReferenceInputMode.MIXED);
        assertThat(state.referenceInputMode())
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.MIXED);
    }

    @Test
    void consumesPendingStoryboardCommitChoiceOnce() {
        AgentScopeTurnState state = new AgentScopeTurnState();

        state.markStoryboardCommitPending(31);

        assertThat(state.takePendingStoryboardCommitCount()).isEqualTo(31);
        assertThat(state.takePendingStoryboardCommitCount()).isNull();
    }
}
