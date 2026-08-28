package com.llmgateway.vendor.video.dto;

/**
 * Laozhang 视频结果 sealed 子类型——与 MiniMax 区分，预留厂商专用字段位（metadata 等）。
 * <p>
 * Laozhang 下载走原生 /videos/{taskId}/content 端点，无直链，故 url 通常为 null。
 */
public record LaozhangVideoResult(
        String vendor,
        String taskId,
        String status,
        String progress,
        String url,
        String error,
        String metadata
) implements VideoVendorResult {
}
