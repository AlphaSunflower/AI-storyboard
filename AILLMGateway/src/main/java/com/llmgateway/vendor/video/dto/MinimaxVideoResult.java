package com.llmgateway.vendor.video.dto;

/**
 * MiniMax 视频结果 sealed 子类型——在公共字段之外保留 MiniMax 专用下载链路信息。
 * <p>
 * {@code contentUrl}：轮询 succeeded 时从 task.content.url 或 file_id 检索到的限时直链（网关据此暂存到 call_log 供下载端点使用）。
 * {@code fileId}：上游返回的文件 id（用于进一步检索 download_url）。
 */
public record MinimaxVideoResult(
        String vendor,
        String taskId,
        String status,
        String progress,
        String url,
        String error,
        String fileId,
        String contentUrl
) implements VideoVendorResult {
}
