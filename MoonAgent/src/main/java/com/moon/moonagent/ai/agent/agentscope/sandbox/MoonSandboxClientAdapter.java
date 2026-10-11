package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import java.util.UUID;

/**
 * AgentScope {@code SandboxClient} that maps sandbox state onto the MoonSandbox runner HTTP API.
 *
 * <p>The runner session id equals the product conversation id, carried per call in
 * {@link WorkspaceSpec#getEnvironment()}. Because the conversation id is persisted inside the
 * sandbox state, {@code resume} can rebuild the same {@code /workspaces/{conversationId}} root
 * and artifact reconciliation keeps working with the conversation id unchanged.
 */
public class MoonSandboxClientAdapter implements SandboxClient<MoonSandboxClientOptions> {

    public static final String CONVERSATION_ENV = "moon.conversationId";
    private static final String WORKSPACE_PREFIX = "/workspaces/";

    private final MoonSandboxClient client;
    private final ObjectMapper mapper;

    public MoonSandboxClientAdapter(MoonSandboxClient client) {
        this.client = client;
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        objectMapper.registerModule(new HarnessSandboxJacksonModule());
        objectMapper.registerSubtypes(
                new NamedType(MoonSandboxState.class, MoonSandboxClientOptions.TYPE));
        this.mapper = objectMapper;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          MoonSandboxClientOptions options) {
        String conversationId = conversationId(workspaceSpec);
        MoonSandboxState state = new MoonSandboxState();
        state.setSessionId(conversationId);
        WorkspaceSpec effective =
                workspaceSpec != null ? workspaceSpec.copy() : new WorkspaceSpec();
        effective.setRoot(workspaceRoot(conversationId));
        state.setWorkspaceSpec(effective);
        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(conversationId));
        }
        state.setWorkspaceRootReady(false);
        return new MoonSandbox(state, client);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof MoonSandboxState moon)) {
            throw new IllegalArgumentException(
                    "Unexpected sandbox state type: " + (state == null ? "null" : state.getClass()));
        }
        WorkspaceSpec spec =
                moon.getWorkspaceSpec() != null ? moon.getWorkspaceSpec() : new WorkspaceSpec();
        spec.setRoot(workspaceRoot(moon.getSessionId()));
        moon.setWorkspaceSpec(spec);
        return new MoonSandbox(moon, client);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // The runner workspace is durable; explicit cleanup is owned by the session lifecycle.
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize MoonSandbox state", error);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return mapper.readValue(json, SandboxState.class);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to deserialize MoonSandbox state", error);
        }
    }

    private static String conversationId(WorkspaceSpec workspaceSpec) {
        if (workspaceSpec != null && workspaceSpec.getEnvironment() != null) {
            String value = workspaceSpec.getEnvironment().get(CONVERSATION_ENV);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    static String workspaceRoot(String conversationId) {
        return WORKSPACE_PREFIX + conversationId;
    }
}
