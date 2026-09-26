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
final class AgentScopeTurnState {

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
    private boolean followUpVideoPlanned;
    private boolean storyboardRequested;
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

    synchronized void addText(String text) {
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

    synchronized boolean hasStartedMedia(String mediaType) {
        return mediaType != null && startedMediaTypes.contains(mediaType);
    }

    synchronized boolean isStoryboardRequested() {
        return storyboardRequested;
    }

    synchronized void markStoryboardRequested() {
        storyboardRequested = true;
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
