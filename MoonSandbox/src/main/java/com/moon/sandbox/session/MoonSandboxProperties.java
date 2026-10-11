package com.moon.sandbox.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "moon.sandbox")
public class MoonSandboxProperties {

    private Path workspaceRoot = Path.of("/workspaces");
    private String pythonExecutable = "python3";
    private String shellExecutable = "sh";
    private String shellFlag = "-c";
    private int maxWriteBytes = 1_048_576;
    private int defaultReadChars = 100_000;
    private int maxReadChars = 200_000;
    private int defaultTimeoutSeconds = 300;
    private int maxTimeoutSeconds = 600;
    private int stdoutLimitBytes = 2_097_152;
    private int stderrLimitBytes = 2_097_152;
    private int maxConcurrentExecutions = 4;
    private Duration idleExpiry = Duration.ofHours(24);
    private String internalSecret = "";

    public static MoonSandboxProperties defaults() {
        return new MoonSandboxProperties();
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable == null || pythonExecutable.isBlank()
                ? "python3" : pythonExecutable;
    }

    public String getShellExecutable() {
        return shellExecutable;
    }

    public void setShellExecutable(String shellExecutable) {
        this.shellExecutable = shellExecutable == null || shellExecutable.isBlank()
                ? "sh" : shellExecutable;
    }

    public String getShellFlag() {
        return shellFlag;
    }

    public void setShellFlag(String shellFlag) {
        this.shellFlag = shellFlag == null || shellFlag.isBlank() ? "-c" : shellFlag;
    }

    public int getMaxWriteBytes() {
        return maxWriteBytes;
    }

    public void setMaxWriteBytes(int maxWriteBytes) {
        this.maxWriteBytes = Math.max(1_024, maxWriteBytes);
    }

    public int getDefaultReadChars() {
        return defaultReadChars;
    }

    public void setDefaultReadChars(int defaultReadChars) {
        this.defaultReadChars = Math.max(1, defaultReadChars);
    }

    public int getMaxReadChars() {
        return maxReadChars;
    }

    public void setMaxReadChars(int maxReadChars) {
        this.maxReadChars = Math.max(1, maxReadChars);
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = Math.max(1, defaultTimeoutSeconds);
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = Math.max(1, maxTimeoutSeconds);
    }

    public int getStdoutLimitBytes() {
        return stdoutLimitBytes;
    }

    public void setStdoutLimitBytes(int stdoutLimitBytes) {
        this.stdoutLimitBytes = Math.max(1_024, stdoutLimitBytes);
    }

    public int getStderrLimitBytes() {
        return stderrLimitBytes;
    }

    public void setStderrLimitBytes(int stderrLimitBytes) {
        this.stderrLimitBytes = Math.max(1_024, stderrLimitBytes);
    }

    public int getMaxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
        this.maxConcurrentExecutions = Math.max(1, maxConcurrentExecutions);
    }

    public Duration getIdleExpiry() {
        return idleExpiry;
    }

    public void setIdleExpiry(Duration idleExpiry) {
        this.idleExpiry = idleExpiry == null || idleExpiry.isNegative() || idleExpiry.isZero()
                ? Duration.ofHours(24) : idleExpiry;
    }

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret == null ? "" : internalSecret;
    }
}
