package com.moon.moonagent.ai.agent.creative.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.MediaCritique;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMediaCritiqueEntity;
import com.moon.moonagent.mapper.AgentMediaCritiqueMapper;
import com.moon.moonagent.security.OutboundUrlGuard;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreativeCritiqueServiceImpl implements CreativeCritiqueService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_REVIEW_IMAGES = 4;

    private final AgentMediaCritiqueMapper mapper;
    private final FileStorageService fileStorageService;
    private final ChatClient.Builder chatClientBuilder;
    private final CreativeModelRouter modelRouter;

    @Autowired(required = false)
    @Qualifier("attachmentHttpClient")
    private HttpClient httpClient;

    @Value("${ai.video.merge.ffmpeg-path:ffmpeg}")
    private String ffmpegPath = "ffmpeg";

    @Value("${ai.video.merge.ffprobe-path:ffprobe}")
    private String ffprobePath = "ffprobe";

    @Override
    public MediaCritique reviewImages(AgentConversation conversation, String turnId, String itemId,
                                      String prompt, List<String> imageUrls,
                                      ConstraintLedger ledger, int repairAttempt) {
        return reviewMedia(conversation, turnId, itemId, "image", prompt,
                imageUrls, ledger, repairAttempt);
    }

    @Override
    public MediaCritique reviewMedia(AgentConversation conversation, String turnId, String itemId,
                                     String mediaType, String prompt, List<String> imageUrls,
                                     ConstraintLedger ledger, int repairAttempt) {
        String effectiveMediaType = mediaType == null || mediaType.isBlank()
                ? "image" : mediaType.trim();
        List<String> urls = imageUrls == null ? List.of() : imageUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .limit(MAX_REVIEW_IMAGES)
                .toList();
        MediaCritique fallback = new MediaCritique(
                null,
                conversation == null ? "" : conversation.getProjectId(),
                conversation == null ? "" : conversation.getId(),
                text(turnId),
                text(itemId),
                effectiveMediaType,
                urls.isEmpty() ? "" : urls.getFirst(),
                "UNVERIFIED",
                false,
                Map.of("overall", 0.5),
                List.of(),
                List.of("视觉批评器未执行，本轮结果按未校验处理"),
                Math.max(0, repairAttempt),
                OffsetDateTime.now());
        if (urls.isEmpty()) return record(fallback);

        try {
            List<Media> media = new ArrayList<>();
            for (String url : urls) {
                String dataUri = fileStorageService.toImageDataUri(url);
                if (dataUri == null || dataUri.isBlank()) continue;
                int separator = dataUri.indexOf(';');
                int comma = dataUri.indexOf(',');
                if (separator <= 5 || comma <= separator) continue;
                media.add(Media.builder()
                        .mimeType(MimeType.valueOf(dataUri.substring(5, separator)))
                        .data(dataUri)
                        .build());
            }
            if (media.isEmpty()) return record(fallback);

            UserMessage userMessage = UserMessage.builder()
                    .text(reviewPrompt(prompt, ledger))
                    .media(media)
                    .build();
            String raw = chatClientBuilder
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(modelRouter.resolve("creative.critic"))
                            .timeout(Duration.ofSeconds(90))
                            .maxRetries(0))
                    .build()
                    .prompt()
                    .system("""
                            You are a strict but practical AIGC media critic.
                            Return JSON only. Do not claim success when evidence is insufficient.
                            """)
                    .messages(userMessage)
                    .call()
                    .content();
            MediaCritique reviewed = parse(conversation, turnId, itemId, effectiveMediaType,
                    urls.getFirst(), raw, repairAttempt);
            return record(reviewed);
        } catch (Exception error) {
            return record(new MediaCritique(
                    null,
                    conversation == null ? "" : conversation.getProjectId(),
                    conversation == null ? "" : conversation.getId(),
                    text(turnId),
                    text(itemId),
                    effectiveMediaType,
                    urls.getFirst(),
                    "UNVERIFIED",
                    false,
                    Map.of("overall", 0.5),
                    List.of(),
                    List.of("视觉批评不可用，本轮降级为未校验：" + rootMessage(error)),
                    Math.max(0, repairAttempt),
                    OffsetDateTime.now()));
        }
    }

    @Override
    public MediaCritique record(MediaCritique critique) {
        AgentMediaCritiqueEntity entity = new AgentMediaCritiqueEntity();
        entity.setId(critique.id());
        entity.setProjectId(blankToNull(critique.projectId()));
        entity.setConversationId(blankToNull(critique.conversationId()));
        entity.setTurnId(blankToNull(critique.turnId()));
        entity.setItemId(text(critique.itemId()));
        entity.setMediaType(text(critique.mediaType()));
        entity.setAssetUrl(text(critique.assetUrl()));
        entity.setDecision(text(critique.decision()));
        entity.setHardConstraintPass(critique.hardConstraintPass());
        entity.setScoreJson(write(critique.scores()));
        entity.setViolationsJson(write(critique.violations()));
        entity.setReasonsJson(write(critique.reasons()));
        entity.setRepairAttempt(Math.max(0, critique.repairAttempt()));
        mapper.insert(entity);
        return new MediaCritique(
                entity.getId(), critique.projectId(), critique.conversationId(),
                critique.turnId(), critique.itemId(), critique.mediaType(), critique.assetUrl(),
                critique.decision(), critique.hardConstraintPass(), critique.scores(),
                critique.violations(), critique.reasons(), critique.repairAttempt(),
                entity.getCreatedAt() == null ? critique.createdAt() : entity.getCreatedAt());
    }

    @Override
    public MediaCritique reviewVideo(AgentConversation conversation, String turnId, String itemId,
                                     String prompt, String videoUrl,
                                     ConstraintLedger ledger, int repairAttempt) {
        if (videoUrl == null || videoUrl.isBlank()) {
            return record(new MediaCritique(
                    null, conversation == null ? "" : conversation.getProjectId(),
                    conversation == null ? "" : conversation.getId(), text(turnId),
                    text(itemId), "video", "", "UNVERIFIED", false,
                    Map.of("overall", 0.0), List.of(),
                    List.of("视频地址为空，无法抽取帧进行校验"),
                    Math.max(0, repairAttempt), OffsetDateTime.now()));
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("moon-video-critique-");
            Path source = work.resolve("source.mp4");
            Files.write(source, readVideo(videoUrl));
            double duration = probeDuration(source);
            List<String> frames = new ArrayList<>();
            double[] positions = {0.05, 0.5, 0.9};
            for (int index = 0; index < positions.length; index++) {
                Path frame = work.resolve("frame-" + index + ".png");
                double seconds = Math.max(0, duration * positions[index]);
                runProcess(List.of(
                        executable(ffmpegPath, "ffmpeg"), "-y",
                        "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                        "-i", source.toString(),
                        "-frames:v", "1",
                        frame.toString()));
                if (Files.isRegularFile(frame)) {
                    frames.add(fileStorageService.saveImageFromBase64(
                            Base64.getEncoder().encodeToString(Files.readAllBytes(frame))));
                }
            }
            if (frames.isEmpty()) {
                throw new IOException("未能从视频抽取任何校验帧");
            }
            return reviewMedia(
                    conversation, turnId, itemId, "video", prompt, frames, ledger, repairAttempt);
        } catch (Exception error) {
            return record(new MediaCritique(
                    null, conversation == null ? "" : conversation.getProjectId(),
                    conversation == null ? "" : conversation.getId(), text(turnId),
                    text(itemId), "video", videoUrl, "UNVERIFIED", false,
                    Map.of("overall", 0.0), List.of(),
                    List.of("视频校验不可用，本轮降级为未校验：" + rootMessage(error)),
                    Math.max(0, repairAttempt), OffsetDateTime.now()));
        } finally {
            deleteRecursively(work);
        }
    }

    @Override
    public List<MediaCritique> listByTurn(String projectId, String conversationId, String turnId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentMediaCritiqueEntity>()
                        .eq(AgentMediaCritiqueEntity::getProjectId, projectId)
                        .eq(AgentMediaCritiqueEntity::getConversationId, conversationId)
                        .eq(AgentMediaCritiqueEntity::getTurnId, turnId)
                        .orderByAsc(AgentMediaCritiqueEntity::getCreatedAt))
                .stream()
                .map(CreativeCritiqueServiceImpl::toView)
                .toList();
    }

    @Override
    public Map<String, Object> asToolResult(MediaCritique critique) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("critiqueId", critique.id());
        result.put("decision", critique.decision());
        result.put("hardConstraintPass", critique.hardConstraintPass());
        result.put("scores", critique.scores());
        result.put("violations", critique.violations());
        result.put("reasons", critique.reasons());
        result.put("repairAttempt", critique.repairAttempt());
        result.put("instruction", critique.repairRequired()
                ? "Repair the generation once with the violations, then review again. If it fails twice, ask the user."
                : "UNVERIFIED".equalsIgnoreCase(critique.decision())
                ? "Do not claim the result passed quality checks. Explain that validation was unavailable."
                : "Continue only when the result satisfies the current request.");
        return result;
    }

    private static MediaCritique parse(AgentConversation conversation, String turnId,
                                       String itemId, String mediaType, String assetUrl, String raw,
                                       int repairAttempt) {
        try {
            String json = extractJson(raw);
            JsonNode root = JSON.readTree(json);
            Map<String, Double> scores = new LinkedHashMap<>();
            JsonNode scoreNode = root.path("scores");
            if (scoreNode.isObject()) {
                scoreNode.fields().forEachRemaining(entry ->
                        scores.put(entry.getKey(), entry.getValue().asDouble(0.0)));
            }
            List<String> violations = strings(root.path("violations"));
            List<String> reasons = strings(root.path("reasons"));
            boolean hardPass = root.path("hardConstraintPass").asBoolean(violations.isEmpty());
            String decision = root.path("decision").asText(
                    hardPass ? "PASS" : "REPAIR").toUpperCase();
            return new MediaCritique(
                    null,
                    conversation == null ? "" : conversation.getProjectId(),
                    conversation == null ? "" : conversation.getId(),
                    text(turnId),
                    text(itemId),
                    mediaType,
                    text(assetUrl),
                    decision,
                    hardPass,
                    scores,
                    violations,
                    reasons,
                    Math.max(0, repairAttempt),
                    OffsetDateTime.now());
        } catch (Exception ignored) {
            return new MediaCritique(
                    null,
                    conversation == null ? "" : conversation.getProjectId(),
                    conversation == null ? "" : conversation.getId(),
                    text(turnId),
                    text(itemId),
                    mediaType,
                    text(assetUrl),
                    "UNVERIFIED",
                    false,
                    Map.of("overall", 0.5),
                    List.of(),
                    List.of("批评器输出无法解析，按未校验处理"),
                    Math.max(0, repairAttempt),
                    OffsetDateTime.now());
        }
    }

    private static String reviewPrompt(String prompt, ConstraintLedger ledger) {
        String hard = ledger == null || ledger.hard().isEmpty()
                ? "none"
                : ledger.hard().stream().map(ConstraintLedger.ConstraintItem::statement).toList().toString();
        return """
                Review the generated image against the request and hard constraints.
                request: %s
                hard constraints: %s

                Return JSON:
                {
                  "decision": "PASS|REPAIR|ASK|REJECT",
                  "hardConstraintPass": true,
                  "scores": {
                    "subject": 0,
                    "composition": 0,
                    "style": 0,
                    "artifacts": 0,
                    "text": 0,
                    "reference": 0,
                    "overall": 0
                  },
                  "violations": [],
                  "reasons": []
                }
                Scores are 0..1. In strict mode any hard-constraint violation means REPAIR.
                """.formatted(text(prompt), hard);
    }

    private static MediaCritique toView(AgentMediaCritiqueEntity entity) {
        return new MediaCritique(
                entity.getId(),
                entity.getProjectId(),
                entity.getConversationId(),
                entity.getTurnId(),
                entity.getItemId(),
                entity.getMediaType(),
                entity.getAssetUrl(),
                entity.getDecision(),
                Boolean.TRUE.equals(entity.getHardConstraintPass()),
                readMap(entity.getScoreJson()),
                readStrings(entity.getViolationsJson()),
                readStrings(entity.getReasonsJson()),
                entity.getRepairAttempt() == null ? 0 : entity.getRepairAttempt(),
                entity.getCreatedAt());
    }

    private static Map<String, Double> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return Map.copyOf(JSON.readValue(value, new TypeReference<Map<String, Double>> () {}));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.copyOf(JSON.readValue(value, new TypeReference<List<String>> () {}));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.asText("").isBlank()) values.add(item.asText(""));
        });
        return List.copyOf(values);
    }

    private static String extractJson(String value) {
        String raw = value == null ? "" : value.strip();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private byte[] readVideo(String source) throws IOException, InterruptedException {
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
        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("下载视频失败，状态码 " + response.statusCode());
        }
        return response.body();
    }

    private double probeDuration(Path video) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                executable(ffprobePath, "ffprobe"),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("ffprobe 失败: " + output);
        try {
            return Math.max(0.1, Double.parseDouble(output.lines().findFirst().orElse("0")));
        } catch (NumberFormatException ignored) {
            return 1.0;
        }
    }

    private static void runProcess(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("ffmpeg 抽帧超时");
        }
        if (process.exitValue() != 0) throw new IOException("ffmpeg 抽帧失败: " + output);
    }

    private static String executable(String configured, String name) {
        String executableName = name + (isWindows() ? ".exe" : "");
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, ".moon-agent", "tools", "ffmpeg", "bin", executableName));
        }
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

    private static String filename(String source) {
        if (source == null) return "";
        int query = source.indexOf('?');
        String clean = query >= 0 ? source.substring(0, query) : source;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
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

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
