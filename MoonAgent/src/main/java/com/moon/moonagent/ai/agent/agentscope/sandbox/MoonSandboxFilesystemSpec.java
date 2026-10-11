package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declarative AgentScope sandbox filesystem configuration for the MoonSandbox runner.
 *
 * <p>Isolation is {@link IsolationScope#SESSION} so the key is the conversation id and a team
 * opening the same conversation reuses the same sandbox workspace. Snapshot persistence is a
 * {@link NoopSnapshotSpec}: the Docker named volume is the source of truth, so each turn must not
 * archive the whole workspace into the distributed store.
 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.sandbox", name = "enabled", havingValue = "true")
public class MoonSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private final MoonSandboxClientAdapter client;
    private final MoonSandboxClientOptions options;

    public MoonSandboxFilesystemSpec(MoonSandboxClient sandboxClient) {
        this.client = new MoonSandboxClientAdapter(sandboxClient);
        this.options = new MoonSandboxClientOptions(client);
        isolationScope(IsolationScope.SESSION);
        snapshotSpec(new NoopSnapshotSpec());
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client;
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return options;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return new NoopSnapshotSpec();
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return new WorkspaceSpec();
    }

    /**
     * Builds the per-call sandbox context. The conversation id rides in the workspace environment
     * so the client can bind {@code /workspaces/{conversationId}} at create/resume time.
     */
    public SandboxContext contextForConversation(String conversationId) {
        WorkspaceSpec workspace = workspaceSpec();
        workspace.setRoot(MoonSandboxClientAdapter.workspaceRoot(conversationId));
        workspace.getEnvironment().put(MoonSandboxClientAdapter.CONVERSATION_ENV, conversationId);
        return SandboxContext.builder()
                .client(client)
                .clientOptions(options)
                .snapshotSpec(snapshotSpec())
                .workspaceSpec(workspace)
                .isolationScope(getIsolationScope())
                .build();
    }
}
