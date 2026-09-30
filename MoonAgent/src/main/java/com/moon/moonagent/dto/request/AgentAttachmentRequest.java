package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * A file attached to one user message. The URL remains the model-facing reference;
 * name/mime metadata is persisted so the conversation can render a stable file row
 * after refresh instead of showing a raw URL.
 */
public record AgentAttachmentRequest(
        @NotBlank(message = "附件 URL 不能为空")
        @Size(max = 2048, message = "附件 URL 过长") String url,
        @Size(max = 200, message = "附件名称过长") String name,
        @Size(max = 100, message = "附件 MIME 类型过长") String mimeType,
        @PositiveOrZero(message = "附件大小不能为负数") Long sizeBytes,
        @Size(max = 32, message = "附件类型过长") String kind,
        @Size(max = 32, message = "附件来源过长") String source,
        @Size(max = 128, message = "附件资产 ID 过长") String assetId,
        @Size(max = 128, message = "附件媒体 ID 过长") String mediaId
) {
}
