package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.security.OutboundUrlGuard;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class VideoMergeService {

    private static final Logger log = LoggerFactory.getLogger(VideoMergeService.class);
    private static final int MAX_SOURCES = 20;

    private final AgentAsyncTaskMapper taskMapper;
    private final FileStorageService fileStorageService;

    @Value("${ai.video.merge.ffmpeg-path:ffmpeg}")
    private String ffmpegPath = "ffmpeg";

    @Value("${ai.video.merge.ffprobe-path:ffprobe}")
    private String ffprobePath = "ffprobe";

    @Value("${ai.video.merge.timeout-seconds:600}")
    private int timeoutSeconds = 600;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public String start(String userId, String conversationId, String projectId,
                        List<String> sourceUrls, Integer expectedDurationSeconds) {
        List<String> sources = sourceUrls == null ? List.of() : sourceUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (sources.isEmpty()) throw new IllegalArgumentException("至少需要一个视频片段");
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("单个合并任务最多支持 " + MAX_SOURCES + " 个片段");
        }
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversationId);
        task.setUserId(userId);
        task.setToolName(AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle("合并完整分镜视频");
        task.setStage("queued");
        task.setProgress(0);
        task.setRequestJson(writeJson(Map.of(
                "projectId", projectId == null ? "" : projectId,
                "sourceUrls", sources,
                "expectedDurationSeconds", expectedDurationSeconds == null ? 0 : expectedDurationSeconds)));
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        executor.submit(() -> run(task.getId(), sources, expectedDurationSeconds));
        return task.getId();
    }

    private void run(String taskId, List<String> sources, Integer expectedDurationSeconds) {
        Path work = null;
        try {
            update(taskId, "running", "merge", 5, null, null, null);
            work = Files.createTempDirectory("moon-video-merge-");
            List<Path> clips = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                Path clip = work.resolve("clip-" + index + ".mp4");
                Files.write(clip, download(sources.get(index)));
                clips.add(clip);
                update(taskId, "running", "merge", 10 + (int) (40.0 * (index + 1) / sources.size()),
                        null, null, null);
            }
            Path list = work.resolve("concat.txt");
            StringBuilder listText = new StringBuilder();
            for (Path clip : clips) {
                listText.append("file '")
                        .append(clip.toAbsolutePath().toString().replace("'", "'\\''"))
                        .append("'\n");
            }
            Files.writeString(list, listText, StandardCharsets.UTF_8);
            Path output = work.resolve("merged.mp4");
            runProcess(List.of(ffmpegExecutable(), "-y", "-f", "concat", "-safe", "0",
                    "-i", list.toString(), "-c", "copy", output.toString()));
            double duration = probeDuration(output);
            if (expectedDurationSeconds != null && expectedDurationSeconds > 0
                    && duration > 0 && Math.abs(duration - expectedDurationSeconds) > 1.0) {
                throw new IllegalStateException("合并成片时长 " + duration
                        + " 秒不符合要求的 " + expectedDurationSeconds + " 秒");
            }
            String url = fileStorageService.saveVideoBytes(Files.readAllBytes(output), "mp4");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", "【阶段4完成】\n\n完整视频已生成：![完整视频](" + url + ")");
            result.put("url", url);
            result.put("durationSeconds", duration);
            result.put("sourceCount", sources.size());
            update(taskId, "completed", "completed", 100, url, writeJson(result), null);
        } catch (Exception e) {
            log.warn("video merge failed taskId={} error={}", taskId, e.getMessage());
            update(taskId, "failed", "failed", 100, null, null, rootMessage(e));
        } finally {
            if (work != null) deleteRecursively(work);
        }
    }

    private byte[] download(String source) throws IOException, InterruptedException {
        String filename = filename(source);
        if (source.startsWith("/api/files/videos/") && !filename.isBlank()) {
            byte[] local = fileStorageService.readVideo(filename);
            if (local != null) return local;
        }
        OutboundUrlGuard.requireSafeHttpUrl(source);
        HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("下载视频失败，状态码 " + response.statusCode() + ": " + source);
        }
        return response.body();
    }

    private double probeDuration(Path output) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                ffprobeExecutable(), "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", output.toString())
                .redirectErrorStream(true)
                .start();
        String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = process.waitFor();
        if (code != 0) throw new IOException("ffprobe 校验失败: " + value);
        try {
            return Double.parseDouble(value.lines().findFirst().orElse("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void runProcess(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("ffmpeg 合并超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg 合并失败: " + output);
        }
    }

    private void update(String taskId, String status, String stage, int progress,
                        String resultUrl, String resultJson, String error) {
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .set("status", status)
                .set("stage", stage)
                .set("progress", progress)
                .set("result_urls_json", resultUrl == null ? null : writeJson(List.of(resultUrl)))
                .set("result_json", resultJson)
                .set("error", error == null || error.isBlank() ? null : error)
                .set("completed_at", "completed".equals(status) || "failed".equals(status)
                        ? OffsetDateTime.now() : null)
                .set("updated_at", OffsetDateTime.now())
                .setSql("version = COALESCE(version, 0) + 1"));
    }

    private String ffmpegExecutable() {
        return executable(ffmpegPath, "ffmpeg");
    }

    private String ffprobeExecutable() {
        return executable(ffprobePath, "ffprobe");
    }

    private static String executable(String configured, String name) {
        String executableName = name + (isWindows() ? ".exe" : "");
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, ".moon-agent", "tools", "ffmpeg", "bin", executableName));
        }
        candidates.add(Path.of(System.getProperty("user.dir"), "tools", "ffmpeg", "bin", executableName));
        String ffmpegHome = System.getenv("FFMPEG_HOME");
        if (ffmpegHome != null && !ffmpegHome.isBlank()) {
            candidates.add(Path.of(ffmpegHome, "bin", executableName));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().toString();
        }
        return configured == null || configured.isBlank() ? executableName : configured;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String filename(String source) {
        int query = source.indexOf('?');
        String clean = query >= 0 ? source.substring(0, query) : source;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
