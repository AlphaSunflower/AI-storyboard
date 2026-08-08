package com.llmgateway.exception;

import lombok.Getter;

/** 业务异常：code + message（GlobalExceptionHandler 统一转 {error:{message}}） */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
