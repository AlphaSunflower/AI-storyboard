package com.moon.moonagent.ai.agent.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxState;

/**
 * AgentScope sandbox state for the MoonSandbox HTTP runner.
 *
 * <p>{@code sessionId} is the product conversation id, so the runner workspace path stays
 * {@code /workspaces/{conversationId}} and artifact reconciliation can keep using the
 * conversation id. Registered for Jackson as the {@code moon-sandbox} sandbox state subtype.
 */
public class MoonSandboxState extends SandboxState {
}
