package com.moon.moonagent.ai.storyboard;

import java.util.List;

/**
 * One ordered, inline-data storyboard shot produced by structured planning.
 */
public record StoryboardShot(
        String shotId,
        String segmentId,
        int shotOrdinal,
        String sceneDescription,
        String shotType,
        String cameraMovement,
        int durationSeconds,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        List<String> referenceHints
) {

    public StoryboardShot {
        referenceHints = referenceHints == null ? List.of() : List.copyOf(referenceHints);
    }
}
