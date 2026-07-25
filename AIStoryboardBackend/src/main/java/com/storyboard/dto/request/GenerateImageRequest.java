package com.storyboard.dto.request;

import java.util.List;

public record GenerateImageRequest(
    String sceneId,
    String prompt,
    String model,
    String size,
    String quality,
    String aspectRatio,
    List<String> referenceImages,
    String mode,
    String generatedImageUrl
) {
    /** 是否为图改图模式（使用 /v1/images/edits multipart 接口） */
    public boolean isEditMode() {
        return "edit".equals(mode);
    }
}
