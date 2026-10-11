package com.moon.sandbox.execution;

import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathResolver;
import com.moon.sandbox.session.SandboxSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxShellExecutionTest {

    @TempDir
    Path root;

    @Test
    void runsShellCommandWithSessionAsWorkingDirectory() {
        MoonSandboxProperties properties = properties();
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        SandboxExecutionService execution = new SandboxExecutionService(sessions, properties);

        ShellResult result = execution.executeShell("c1", workingDirectoryCommand(), 30);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("c1");
    }

    @Test
    void reportsNonZeroExitCode() {
        MoonSandboxProperties properties = properties();
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        SandboxExecutionService execution = new SandboxExecutionService(sessions, properties);

        ShellResult result = execution.executeShell("c1", "exit 3", 30);

        assertThat(result.exitCode()).isEqualTo(3);
    }

    private MoonSandboxProperties properties() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(root);
        if (isWindows()) {
            properties.setShellExecutable("cmd");
            properties.setShellFlag("/c");
        }
        return properties;
    }

    private static String workingDirectoryCommand() {
        return isWindows() ? "cd" : "pwd";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
