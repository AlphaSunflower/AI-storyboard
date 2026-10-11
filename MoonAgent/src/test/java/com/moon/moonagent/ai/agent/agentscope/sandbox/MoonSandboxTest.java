package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxShellResult;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MoonSandboxTest {

    @Test
    void rewritesWindowsCdPrefixForLinuxRunner() {
        assertThat(MoonSandbox.normalizeCommand("cd /d \"output\" && ls"))
                .isEqualTo("cd 'output' && ls");
    }

    @Test
    void leavesPosixCommandUntouched() {
        assertThat(MoonSandbox.normalizeCommand("ls output")).isEqualTo("ls output");
    }

    @Test
    void acceptsWorkspacePathsAndRejectsForeignAbsolutePaths() {
        MoonSandbox sandbox = sandbox(mock(MoonSandboxClient.class));

        assertThat(sandbox.supportsFileTransfer("/workspaces/c1/output/x.md")).isTrue();
        assertThat(sandbox.supportsFileTransfer("output/x.md")).isTrue();
        assertThat(sandbox.supportsFileTransfer("/etc/passwd")).isFalse();
    }

    @Test
    void mapsShellResultToExecResult() throws Exception {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        when(client.shell("c1", "echo hi", 5))
                .thenReturn(new SandboxShellResult(0, "hi\n", "", false, false, 3));

        ExecResult result = sandbox(client).exec(null, "echo hi", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hi\n");
        assertThat(result.truncated()).isFalse();
    }

    private static MoonSandbox sandbox(MoonSandboxClient client) {
        MoonSandboxState state = new MoonSandboxState();
        state.setSessionId("c1");
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/workspaces/c1");
        state.setWorkspaceSpec(spec);
        return new MoonSandbox(state, client);
    }
}
