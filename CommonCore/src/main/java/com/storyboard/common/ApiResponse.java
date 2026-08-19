package com.storyboard.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 统一 API 响应包装（微服务公共契约，主后端语义：成功 code=200 + timestamp）。
 * <p>
 * 注意：LLM 网关（AILLMGateway）的 /v1、/admin API 使用独立契约
 * （code=0 成功、无 timestamp），不引用本类——两套契约保持各自语义。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    int code,
    String message,
    T data,
    OffsetDateTime timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, OffsetDateTime.now());
    }
}
