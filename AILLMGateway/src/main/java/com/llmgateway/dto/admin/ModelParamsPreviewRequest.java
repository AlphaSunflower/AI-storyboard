package com.llmgateway.dto.admin;

/** 模型配置试运行：用一段真实上游响应验证 params_schema 的归一化映射 */
public record ModelParamsPreviewRequest(
        String paramsSchema,
        String sample,
        String kind,     // sync | async
        String taskId) {
}
