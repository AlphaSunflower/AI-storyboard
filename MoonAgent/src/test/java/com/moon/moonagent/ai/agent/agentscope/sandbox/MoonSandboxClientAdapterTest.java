package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MoonSandboxClientAdapterTest {

    private final MoonSandboxClientAdapter adapter =
            new MoonSandboxClientAdapter(mock(MoonSandboxClient.class));

    @Test
    void bindsConversationIdAndWorkspaceRootOnCreate() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEnvironment().put(MoonSandboxClientAdapter.CONVERSATION_ENV, "conv-1");

        Sandbox sandbox = adapter.create(
                spec, new NoopSnapshotSpec(), new MoonSandboxClientOptions(adapter));

        assertThat(sandbox.getState().getSessionId()).isEqualTo("conv-1");
        assertThat(sandbox.getState().getWorkspaceSpec().getRoot())
                .isEqualTo("/workspaces/conv-1");
    }

    @Test
    void roundTripsStateAndRebuildsRootOnResume() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEnvironment().put(MoonSandboxClientAdapter.CONVERSATION_ENV, "conv-2");
        Sandbox created = adapter.create(
                spec, new NoopSnapshotSpec(), new MoonSandboxClientOptions(adapter));

        String json = adapter.serializeState(created.getState());
        SandboxState restored = adapter.deserializeState(json);
        Sandbox resumed = adapter.resume(restored);

        assertThat(resumed.getState().getSessionId()).isEqualTo("conv-2");
        assertThat(resumed.getState().getWorkspaceSpec().getRoot())
                .isEqualTo("/workspaces/conv-2");
    }
}
