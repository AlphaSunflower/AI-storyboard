package com.llmgateway.dto;

/** 管理 API 统一响应包装 */
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "success", data); }
    public static <T> ApiResponse<T> error(int code, String message) { return new ApiResponse<>(code, message, null); }
}
