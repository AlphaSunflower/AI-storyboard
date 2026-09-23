package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAsyncTaskService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentAsyncTaskMapper taskMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentGenerationService generationService;
    private final AgentOrchestratorSupport support;
    private final AgentScopeProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public String startBatchImageTask(
            AgentConversation conversation,
            String prompt,
            List<String> prompts,
            List<String> sourceUrls,
            String size,
            String quality,
            int count,
            boolean followUpVideo,
            String videoPrompt,
            String videoDuration,
            String videoAspectRatio,
            String videoResolution,
            String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt == null ? "" : prompt);
        request.put("prompts", prompts == null ? List.of() : prompts);
        request.put("sourceUrls", sourceUrls == null ? List.of() : sourceUrls);
        request.put("size", size == null ? "" : size);
        request.put("quality", quality == null ? "" : quality);
        request.put("count", count);
        request.put("followUpVideo", followUpVideo);
        request.put("videoPrompt", videoPrompt == null ? "" : videoPrompt);
        request.put("videoDuration", videoDuration == null ? "" : videoDuration);
        request.put("videoAspectRatio", videoAspectRatio == null ? "" : videoAspectRatio);
        request.put("videoResolution", videoResolution == null ? "" : videoResolution);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + requestJson).getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setStatus("queued");
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        executor.submit(() -> runBatchImageTask(task.getId()));
        return task.getId();
    }

    public Map<String, Object> getStatus(String userId, String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return Map.of();
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId())) return Map.of();
        return statusMap(task);
    }

    public Map<String, Object> getConversationStatus(String conversationId, String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !conversationId.equals(task.getConversationId())) return Map.of();
        return statusMap(task);
    }

    @Scheduled(fixedDelayString = "${ai.agent.agentscope.async-recovery-delay-ms:30000}",
            initialDelayString = "${ai.agent.agentscope.async-recovery-initial-delay-ms:15000}")
    void recoverPendingBatchImages() {
        if (!properties.isAsyncEnabled()) return;
        List<AgentAsyncTask> queued = taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getKind, "image-batch")
                .eq(AgentAsyncTask::getStatus, "queued")
                .orderByAsc(AgentAsyncTask::getCreatedAt)
                .last("LIMIT 20"));
        for (AgentAsyncTask task : queued) {
            executor.submit(() -> runBatchImageTask(task.getId()));
        }

        List<AgentAsyncTask> staleRunning = taskMapper.selectList(
                new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "image-batch")
                        .eq(AgentAsyncTask::getStatus, "running")
                        .lt(AgentAsyncTask::getUpdatedAt, OffsetDateTime.now().minusMinutes(15))
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT 20"));
        for (AgentAsyncTask task : staleRunning) {
            fail(task.getId(), "服务重启后无法安全确认原任务状态，请重新发起批量生图。");
        }
    }

    private void runBatchImageTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .eq("status", "queued")
                .set("status", "running")
                .set("updated_at", OffsetDateTime.now()));
        if (claimed == 0) return;
        try {
            Map<String, Object> request = readMap(task.getRequestJson());
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            List<String> sources = stringList(request.get("sourceUrls"));
            int count = integer(request.get("count"), 1);
            List<String> prompts = stringList(request.get("prompts"));
            if (prompts.isEmpty()) {
                String prompt = string(request.get("prompt"));
                prompts = java.util.stream.IntStream.range(0, count)
                        .mapToObj(index -> prompt)
                        .toList();
            }
            List<String> urls = new java.util.ArrayList<>();
            List<String> assetIds = new java.util.ArrayList<>();
            for (String prompt : prompts) {
                Map<String, Object> result = generationService.generateImage(
                        conversation, null, prompt, null,
                        string(request.get("size")), string(request.get("quality")),
                        1, sources.isEmpty() ? null : "edit", sources, null);
                stringList(result.get("imageUrls")).forEach(url -> {
                    if (!urls.contains(url)) urls.add(url);
                });
                stringList(result.get("assetIds")).forEach(id -> {
                    if (!assetIds.contains(id)) assetIds.add(id);
                });
            }
            String content = urls.stream()
                    .map(url -> "![生成图片](" + url + ")")
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("批量生图已完成，但未返回图片。");
            String followUpTaskId = startFollowUpVideo(conversation, request, urls);
            if (!followUpTaskId.isBlank()) {
                content += "\n\n图片已生成，首尾帧视频任务已提交，任务 ID：`"
                        + followUpTaskId + "`。";
            }
            complete(taskId, content, assetIds, urls, followUpTaskId);
        } catch (Exception e) {
            log.warn("Batch image task failed: taskId={}, error={}", taskId, e.getMessage());
            fail(taskId, e.getMessage() == null ? "批量生图失败" : e.getMessage());
        }
    }

    private String startFollowUpVideo(
            AgentConversation conversation,
            Map<String, Object> request,
            List<String> urls) {
        if (!Boolean.TRUE.equals(request.get("followUpVideo")) || urls.size() < 2) return "";
        try {
            String prompt = string(request.get("videoPrompt"));
            if (prompt.isBlank()) {
                prompt = string(request.get("prompt"))
                        + "\n真实转播质感，首尾帧人物、球衣和场景保持连续。";
            }
            String duration = string(request.get("videoDuration"));
            String aspectRatio = string(request.get("videoAspectRatio"));
            String resolution = string(request.get("videoResolution"));
            Map<String, String> params = new LinkedHashMap<>();
            if (!resolution.isBlank()) params.put("resolution", resolution);
            OrchestrationRequest videoRequest =
                    new OrchestrationRequest(conversation, prompt, List.of(), null);
            String taskId = support.startVideoGenerationAsyncWithRole(
                    videoRequest,
                    prompt,
                    duration,
                    aspectRatio,
                    List.of(
                            ReferenceImage.ofFirstFrame(urls.get(0)),
                            ReferenceImage.ofLastFrame(urls.get(1))),
                    List.of(),
                    params);
            return taskId == null ? "" : taskId;
        } catch (Exception e) {
            log.warn("Batch image follow-up video failed: taskId={}, error={}",
                    conversation == null ? "" : conversation.getId(), e.getMessage());
            return "";
        }
    }

    private void complete(
            String taskId,
            String content,
            List<String> assetIds,
            List<String> urls,
            String followUpTaskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"running".equals(task.getStatus())) return;
        String messageId = persistTerminalMessage(task, content, "completed", assetIds, urls, null);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .eq("status", "running")
                .set("status", "completed")
                .set("result_json", writeJson(Map.of(
                        "content", content,
                        "followUpTaskId", followUpTaskId == null ? "" : followUpTaskId)))
                .set("asset_ids_json", writeJson(assetIds))
                .set("result_urls_json", writeJson(urls))
                .set("message_id", messageId)
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
    }

    private void fail(String taskId, String error) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || "completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            return;
        }
        String content = "批量生图失败：" + error;
        String messageId = persistTerminalMessage(task, content, "failed", List.of(), List.of(), error);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .ne("status", "completed")
                .set("status", "failed")
                .set("error", error)
                .set("message_id", messageId)
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
    }

    private String persistTerminalMessage(
            AgentAsyncTask task,
            String content,
            String status,
            List<String> assetIds,
            List<String> resultUrls,
            String error) {
        if (task == null) return "";
        AgentMessage message = new AgentMessage();
        message.setConversationId(task.getConversationId());
        message.setRole("assistant");
        message.setContent(content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "assistant-reply");
        metadata.put("asyncTaskId", task.getId());
        metadata.put("asyncTask", Map.of(
                "taskId", task.getId(),
                "kind", task.getKind(),
                "status", status,
                "assetIds", assetIds,
                "resultUrls", resultUrls,
                "error", error == null ? "" : error));
        message.setMetadata(metadata);
        try {
            messageMapper.insert(message);
        } catch (Exception duplicate) {
            AgentMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, task.getConversationId())
                    .apply("metadata->>'asyncTaskId' = {0}", task.getId())
                    .last("LIMIT 1"));
            return existing == null ? "" : existing.getId();
        }
        return message.getId();
    }

    private static Map<String, Object> statusMap(AgentAsyncTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("kind", task.getKind());
        out.put("toolName", task.getToolName());
        out.put("status", task.getStatus());
        out.put("assetIds", readStringList(task.getAssetIdsJson()));
        List<String> urls = readStringList(task.getResultUrlsJson());
        out.put("resultUrls", urls);
        out.put("url", urls.isEmpty() ? "" : urls.getFirst());
        Map<String, Object> result = readMap(task.getResultJson());
        out.put("result", string(result.get("content")));
        out.put("followUpTaskId", string(result.get("followUpTaskId")));
        out.put("messageId", task.getMessageId() == null ? "" : task.getMessageId());
        out.put("error", task.getError() == null ? "" : task.getError());
        return out;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return JSON.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
