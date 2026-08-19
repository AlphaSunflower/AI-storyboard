package com.storyboard.common;

/**
 * 业务异常 — 携带业务错误码，由全局异常处理器映射为 HTTP 状态码。
 * 错误码段约定：40001 参数错误 / 40301 无权限 / 40401 不存在 / 40901 冲突 / 401xx 未认证 / 502xx 上游错误。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 携带底层原因的业务异常（上游错误包装用） */
    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
