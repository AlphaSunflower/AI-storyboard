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
        List<String> referenceHints,
        /**
         * 本镜与前镜的衔接说明（继承项 / 禁改项）。
         *
         * <p>跨镜一致性此前没有任何载体：同一角色在不同镜头里长得不一样，根因之一就是
         * 「上一镜的雨、湿发、光位要不要延续」全靠模型每镜自由发挥。
         * 独立成字段而不是混进提示词，才能单独审阅、单独改。
         */
        String continuityNote
) {

    public StoryboardShot {
        referenceHints = referenceHints == null ? List.of() : List.copyOf(referenceHints);
        continuityNote = continuityNote == null ? "" : continuityNote.trim();
    }

    /** 向后兼容：没有衔接说明时等价于空串，现有构造点无需改动。 */
    public StoryboardShot(
            String shotId, String segmentId, int shotOrdinal, String sceneDescription,
            String shotType, String cameraMovement, int durationSeconds,
            String imagePrompt, String videoPrompt, String negativePrompt,
            List<String> referenceHints) {
        this(shotId, segmentId, shotOrdinal, sceneDescription, shotType, cameraMovement,
                durationSeconds, imagePrompt, videoPrompt, negativePrompt, referenceHints, "");
    }
}
