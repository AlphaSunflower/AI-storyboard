package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxShellResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope {@code Sandbox} backed by the MoonSandbox HTTP runner.
 *
 * <p>The runner workspace is durable (Docker named volume {@code sandbox-workspaces}), so
 * {@code shutdown} must never delete it. {@code persistWorkspace}/{@code hydrateWorkspace} forward
 * to the runner archive endpoints; with the {@code NoopSnapshotSpec} in use they are only exercised
 * by manual export/import and future cross-node snapshots.
 */
public class MoonSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Pattern WINDOWS_CD = Pattern.compile(
            "^\\s*cd\\s+/d\\s+\"([^\"]*)\"\\s*&&\\s*(.*)$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final MoonSandboxClient client;
    private final MoonSandboxState moonState;

    public MoonSandbox(MoonSandboxState state, MoonSandboxClient client) {
        super(state);
        this.moonState = state;
        this.client = client;
    }

    private String conversationId() {
        return moonState.getSessionId();
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds) {
        SandboxShellResult result = client.shell(
                conversationId(), normalizeCommand(command), timeoutSeconds);
        return new ExecResult(
                result.exitCode(), result.stdout(), result.stderr(), result.truncated());
    }

    @Override
    protected InputStream doPersistWorkspace() {
        return new ByteArrayInputStream(client.exportArchive(conversationId()));
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        client.importArchive(conversationId(),
                archive == null ? new byte[0] : archive.readAllBytes());
    }

    @Override
    protected void doSetupWorkspace() {
        // The runner creates /workspaces/{conversationId} on first access.
    }

    @Override
    protected void doDestroyWorkspace() {
        // Durable named volume: shutdown must not delete the conversation workspace.
    }

    @Override
    protected String getWorkspaceRoot() {
        return moonState.getWorkspaceSpec().getRoot();
    }

    @Override
    public boolean supportsFileTransfer(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            String root = getWorkspaceRoot();
            return normalized.equals(root) || normalized.startsWith(root + "/");
        }
        return !normalized.contains("..");
    }

    @Override
    public void uploadFile(String path, byte[] content) {
        client.upload(conversationId(), relativePath(path), content);
    }

    @Override
    public byte[] downloadFile(String path) {
        return client.download(conversationId(), relativePath(path));
    }

    private String relativePath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        String root = getWorkspaceRoot();
        if (normalized.equals(root)) {
            return "";
        }
        if (normalized.startsWith(root + "/")) {
            return normalized.substring(root.length() + 1);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Rewrites the Windows {@code cd /d "dir" && cmd} prefix that {@code ShellExecuteTool}
     * generates from the host JVM {@code os.name} into a POSIX {@code cd 'dir' && cmd}, so the
     * official shell tool's {@code working_directory} parameter works inside the Linux runner.
     */
    static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        Matcher matcher = WINDOWS_CD.matcher(command);
        if (!matcher.matches()) {
            return command;
        }
        String workingDirectory = matcher.group(1).replace("'", "'\\''");
        return "cd '" + workingDirectory + "' && " + matcher.group(2);
    }
}
