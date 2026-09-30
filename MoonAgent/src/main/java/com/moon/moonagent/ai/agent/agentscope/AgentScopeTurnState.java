package com.moon.moonagent.ai.agent.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable per-turn user-visible output. Model text is streamed separately, while
 * deterministic prefaces and media artifacts are recorded here so message_end
 * persists the same content the SSE stream displayed.
 */
public final class AgentScopeTurnState {

    enum ReferenceInputMode {
        NONE,
        UPLOAD_ONLY,
        ASSET_ONLY,
        MIXED
    }

    private final List<String> segments = new ArrayList<>();
    private final List<Map<String, Object>> taskCards = new ArrayList<>();
    private final Set<String> mediaUrls = new LinkedHashSet<>();
    private final Set<String> startedMediaTypes = new LinkedHashSet<>();
    private final Map<String, Integer> mediaAttempts = new HashMap<>();
    private final Set<String> repairAuthorized = new LinkedHashSet<>();
    private boolean followUpVideoPlanned;
    private boolean storyboardRequested;
    private Integer pendingStoryboardCommitCount;
    private ReferenceInputMode referenceInputMode = ReferenceInputMode.NONE;
    private final Map<String, Integer> preflightFailures = new HashMap<>();

    synchronized void setReferenceInputMode(ReferenceInputMode mode) {
        referenceInputMode = mode == null ? ReferenceInputMode.NONE : mode;
    }

    synchronized ReferenceInputMode referenceInputMode() {
        return referenceInputMode;
    }
    synchronized void addImage(String url, String alt) {
        if (url == null || url.isBlank() || !mediaUrls.add(url)) return;
        segments.add("![" + (alt == null || alt.isBlank() ? "生成图片" : alt) + "](" + url + ")");
    }

    public synchronized void addText(String text) {
        if (text == null || text.isBlank()) return;
        segments.add(text.trim());
    }

    synchronized void addTaskCard(String taskId, String title, String kind) {
        if (taskId == null || taskId.isBlank()) return;
        taskCards.add(Map.of(
                "taskId", taskId,
                "title", title == null ? "" : title,
                "kind", kind == null ? "" : kind));
    }

    synchronized List<Map<String, Object>> taskCards() {
        return List.copyOf(taskCards);
    }

    synchronized boolean hasTaskIds() {
        return taskCards.stream().anyMatch(card -> {
            Object taskId = card.get("taskId");
            return taskId != null && !String.valueOf(taskId).isBlank();
        });
    }

    synchronized boolean hasStartedMedia(String mediaType) {
        return mediaType != null && startedMediaTypes.contains(mediaType);
    }

    synchronized boolean beginMedia(String mediaType, int maxAttempts) {
        if (mediaType == null || mediaType.isBlank()) return true;
        int attempts = mediaAttempts.getOrDefault(mediaType, 0);
        if (attempts >= Math.max(1, maxAttempts)) return false;
        if (attempts > 0 && !repairAuthorized.remove(mediaType)) return false;
        mediaAttempts.put(mediaType, attempts + 1);
        startedMediaTypes.add(mediaType);
        return true;
    }

    synchronized void authorizeMediaRepair(String mediaType) {
        if (mediaType != null && !mediaType.isBlank()) repairAuthorized.add(mediaType);
    }

    synchronized void rollbackMediaAttempt(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return;
        int attempts = mediaAttempts.getOrDefault(mediaType, 0);
        if (attempts <= 1) mediaAttempts.remove(mediaType);
        else mediaAttempts.put(mediaType, attempts - 1);
        if (!mediaAttempts.containsKey(mediaType)) startedMediaTypes.remove(mediaType);
    }

    synchronized int mediaAttempts(String mediaType) {
        return mediaType == null ? 0 : mediaAttempts.getOrDefault(mediaType, 0);
    }

    synchronized boolean mediaRepairAuthorized(String mediaType) {
        return mediaType != null && repairAuthorized.contains(mediaType);
    }

    synchronized boolean isStoryboardRequested() {
        return storyboardRequested;
    }

    synchronized void markStoryboardRequested() {
        storyboardRequested = true;
    }

    synchronized void markStoryboardCommitPending(int existingSceneCount) {
        pendingStoryboardCommitCount = Math.max(0, existingSceneCount);
    }

    synchronized Integer takePendingStoryboardCommitCount() {
        Integer value = pendingStoryboardCommitCount;
        pendingStoryboardCommitCount = null;
        return value;
    }

    synchronized void markMediaStarted(String mediaType) {
        if (mediaType != null) startedMediaTypes.add(mediaType);
    }

    synchronized boolean tryPlanFollowUpVideo() {
        if (followUpVideoPlanned || startedMediaTypes.contains("video")) return false;
        followUpVideoPlanned = true;
        return true;
    }

    synchronized boolean isFollowUpVideoPlanned() {
        return followUpVideoPlanned;
    }

    synchronized void recordPreflightFailure(String toolName) {
        if (toolName == null || toolName.isBlank()) return;
        preflightFailures.merge(toolName, 1, Integer::sum);
    }

    synchronized int preflightFailures(String toolName) {
        return preflightFailures.getOrDefault(toolName, 0);
    }

    synchronized String compose(String finalModelText) {
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) continue;
            if (!out.isEmpty()) out.append("\n\n");
            out.append(segment.trim());
        }
        if (finalModelText != null && !finalModelText.isBlank()) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append(finalModelText.trim());
        }
        return out.toString();
    }
}
