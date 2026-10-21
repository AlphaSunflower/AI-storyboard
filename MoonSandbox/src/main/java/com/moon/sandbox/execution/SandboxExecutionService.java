package com.moon.sandbox.execution;

import com.moon.sandbox.session.FileEntry;
import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathException;
import com.moon.sandbox.session.SandboxSessionService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class SandboxExecutionService {

    private final SandboxSessionService sessions;
    private final MoonSandboxProperties properties;
    private final Semaphore admission;

    public SandboxExecutionService(SandboxSessionService sessions,
                                   MoonSandboxProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
        this.admission = new Semaphore(properties.getMaxConcurrentExecutions(), true);
    }

    public ExecutionResult execute(String conversationId, String script,
                                   List<String> arguments, int requestedTimeoutSeconds) {
        boolean acquired = false;
        try {
            acquired = admission.tryAcquire(
                    Math.max(1, properties.getDefaultTimeoutSeconds()), TimeUnit.SECONDS);
            if (!acquired) {
                throw new SandboxPathException("sandbox execution capacity is full");
            }
            return sessions.withLock(conversationId,
                    () -> executeLocked(conversationId, script, arguments, requestedTimeoutSeconds));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SandboxPathException("sandbox execution interrupted", error);
        } finally {
            if (acquired) admission.release();
        }
    }

    public ShellResult executeShell(String conversationId, String command,
                                    int requestedTimeoutSeconds) {
        boolean acquired = false;
        try {
            acquired = admission.tryAcquire(
                    Math.max(1, properties.getDefaultTimeoutSeconds()), TimeUnit.SECONDS);
            if (!acquired) {
                throw new SandboxPathException("sandbox execution capacity is full");
            }
            return sessions.withLock(conversationId,
                    () -> executeShellLocked(conversationId, command, requestedTimeoutSeconds));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SandboxPathException("sandbox execution interrupted", error);
        } finally {
            if (acquired) admission.release();
        }
    }

    private ShellResult executeShellLocked(String conversationId, String command,
                                           int requestedTimeoutSeconds) {
        Path session = sessions.sessionRoot(conversationId);
        Path tmpDir = session.resolve("tmp");
        try {
            Files.createDirectories(tmpDir);
            List<String> argv = new ArrayList<>();
            argv.add(properties.getShellExecutable());
            argv.add(properties.getShellFlag());
            argv.add(command == null ? "" : command);
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(session.toFile());
            builder.redirectErrorStream(false);
            builder.environment().put("PYTHONPATH", session.resolve("vendor").toString());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("HOME", session.toString());
            builder.environment().put("TMPDIR", tmpDir.toString());
            long started = System.nanoTime();
            Process process = builder.start();
            CompletableFuture<LimitedOutput> stdout = readAsync(
                    process.getInputStream(), properties.getStdoutLimitBytes());
            CompletableFuture<LimitedOutput> stderr = readAsync(
                    process.getErrorStream(), properties.getStderrLimitBytes());
            int timeout = requestedTimeoutSeconds <= 0
                    ? properties.getDefaultTimeoutSeconds()
                    : Math.min(requestedTimeoutSeconds, properties.getMaxTimeoutSeconds());
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            boolean timedOut = !finished;
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            LimitedOutput out = stdout.join();
            LimitedOutput err = stderr.join();
            int exitCode = finished ? process.exitValue() : -1;
            sessions.touch(conversationId);
            return new ShellResult(
                    exitCode,
                    out.text(),
                    err.text(),
                    out.truncated() || err.truncated(),
                    timedOut,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (IOException error) {
            throw new SandboxPathException(
                    "unable to execute shell command: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SandboxPathException("shell execution interrupted", error);
        } finally {
            cleanupOldFiles(tmpDir, properties.getTransientTtl());
        }
    }

    private ExecutionResult executeLocked(String conversationId, String script,
                                          List<String> arguments, int requestedTimeoutSeconds) {
        Path session = sessions.sessionRoot(conversationId);
        Path runnerDir = session.resolve("work").resolve(".runner");
        Path scriptPath = runnerDir.resolve(UUID.randomUUID() + ".py");
        Path tmpDir = session.resolve("tmp");
        try {
            Files.createDirectories(runnerDir);
            Files.createDirectories(tmpDir);
            Files.writeString(scriptPath, script == null ? "" : script, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add(properties.getPythonExecutable());
            command.add(scriptPath.toAbsolutePath().toString());
            if (arguments != null) command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(session.toFile());
            builder.redirectErrorStream(false);
            builder.environment().put("PYTHONPATH", session.resolve("vendor").toString());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("HOME", session.toString());
            builder.environment().put("TMPDIR", tmpDir.toString());
            long started = System.nanoTime();
            Process process = builder.start();
            CompletableFuture<LimitedOutput> stdout = readAsync(
                    process.getInputStream(), properties.getStdoutLimitBytes());
            CompletableFuture<LimitedOutput> stderr = readAsync(
                    process.getErrorStream(), properties.getStderrLimitBytes());
            int timeout = requestedTimeoutSeconds <= 0
                    ? properties.getDefaultTimeoutSeconds()
                    : Math.min(requestedTimeoutSeconds, properties.getMaxTimeoutSeconds());
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            boolean timedOut = !finished;
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            LimitedOutput out = stdout.join();
            LimitedOutput err = stderr.join();
            int exitCode = finished ? process.exitValue() : -1;
            sessions.touch(conversationId);
            return new ExecutionResult(
                    exitCode,
                    out.text(),
                    err.text(),
                    out.truncated(),
                    err.truncated(),
                    timedOut,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    files(conversationId));
        } catch (IOException error) {
            throw new SandboxPathException(
                    "unable to execute python script: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SandboxPathException("python execution interrupted", error);
        } finally {
            // 每次执行都写一个 UUID 脚本；不删就会在 work/.runner/ 里越积越多。
            deleteQuietly(scriptPath);
            cleanupOldFiles(tmpDir, properties.getTransientTtl());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删除失败不覆盖原始执行结果；下一轮清理任务会再试
        }
    }

    /** 只清超过 TTL 的临时文件，避免误删仍在执行的进程刚写下的中间文件。 */
    private static void cleanupOldFiles(Path directory, Duration ttl) {
        if (!Files.isDirectory(directory)) return;
        Instant cutoff = Instant.now().minus(ttl);
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path)) continue;
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // 正在被占用：留给下一轮
                }
            }
        } catch (IOException ignored) {
            // 目录不可读：不影响执行结果
        }
    }

    private List<FileEntryView> files(String conversationId) {        return sessions.listOutput(conversationId).stream()
                .map(this::view)
                .toList();
    }

    private FileEntryView view(FileEntry file) {
        return new FileEntryView(file.path(), file.sizeBytes(), file.mimeType());
    }

    private static CompletableFuture<LimitedOutput> readAsync(InputStream stream, int limit) {
        return CompletableFuture.supplyAsync(() -> readLimited(stream, limit));
    }

    private static LimitedOutput readLimited(InputStream stream, int limit) {
        try (stream; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                int remaining = limit - kept.size();
                if (remaining > 0) {
                    kept.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) truncated = true;
            }
            return new LimitedOutput(
                    kept.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException error) {
            throw new SandboxPathException("unable to read process output", error);
        }
    }

    private record LimitedOutput(String text, boolean truncated) {
    }
}
