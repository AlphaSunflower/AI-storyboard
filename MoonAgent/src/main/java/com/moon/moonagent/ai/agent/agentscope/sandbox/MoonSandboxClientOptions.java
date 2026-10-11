package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

/**
 * Options for the MoonSandbox runner backend.
 *
 * <p>The client is supplied by {@link MoonSandboxFilesystemSpec}; the framework never needs to
 * self-instantiate it, but {@link #createClient()} returns the injected instance when present so
 * both wiring paths stay valid.
 */
public class MoonSandboxClientOptions extends SandboxClientOptions {

    public static final String TYPE = "moon-sandbox";
    public static final String BASE_WORKSPACE_ROOT = "/workspaces";

    @JsonIgnore
    private transient MoonSandboxClientAdapter client;

    public MoonSandboxClientOptions() {
    }

    public MoonSandboxClientOptions(MoonSandboxClientAdapter client) {
        this.client = client;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "MoonSandboxClientAdapter must be provided by MoonSandboxFilesystemSpec");
        }
        return client;
    }

    @Override
    public String getWorkspaceRoot() {
        return BASE_WORKSPACE_ROOT;
    }
}
