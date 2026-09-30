package com.moon.moonagent.ai.agent.creative;

import java.time.OffsetDateTime;
import java.util.List;

public record TimelineManifest(
        String id,
        String projectId,
        String conversationId,
        String turnId,
        String title,
        String status,
        int totalDurationSeconds,
        List<Shot> shots,
        OffsetDateTime updatedAt
) {
    public TimelineManifest {
        shots = shots == null ? List.of() : List.copyOf(shots);
    }

    public List<String> sourceUrls() {
        return shots.stream()
                .map(Shot::sourceUrl)
                .filter(url -> url != null && !url.isBlank())
                .toList();
    }

    public record Shot(
            String itemId,
            int order,
            String sourceUrl,
            int startSeconds,
            int durationSeconds,
            double speed,
            String transition,
            String note
    ) {
        public Shot {
            itemId = text(itemId);
            sourceUrl = text(sourceUrl);
            startSeconds = Math.max(0, startSeconds);
            durationSeconds = Math.max(0, durationSeconds);
            speed = speed <= 0 ? 1.0 : speed;
            transition = text(transition);
            note = text(note);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
