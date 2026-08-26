package com.moon.xiaoyuecanvas.dto.response;

/**
 * Morrow 作业提交响应（契约 {@code POST /jobs} → {@code {jobId}}）。
 */
public record JobSubmitResponse(String jobId) {
}
