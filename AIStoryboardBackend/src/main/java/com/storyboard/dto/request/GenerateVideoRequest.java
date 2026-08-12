package com.storyboard.dto.request;

import java.util.List;

public record GenerateVideoRequest(
    String sceneId,
    String prompt,
    String model,
    String resolution,
    String size,
    String aspectRatio,
    Integer duration,
    String negativePrompt,
    Long seed,
    List<String> referenceImages,
    String generatedImageUrl,
    List<String> referenceVideos,
    List<String> referenceAudios
) {}