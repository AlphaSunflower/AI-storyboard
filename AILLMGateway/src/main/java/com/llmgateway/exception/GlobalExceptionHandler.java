package com.llmgateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** 统一异常处理：输出 OAI 风格 {error:{message}} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> err(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("message", message)));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        // 40001→400 / 40301→403 / 40401→404 / 50301→503（渠道不可用）/ 其余→500
        int status = switch (e.getCode()) {
            case 40001 -> 400;
            case 40301 -> 403;
            case 40401 -> 404;
            case 50301 -> 503;
            default -> 500;
        };
        String msg = e.getMessage() == null ? "business error" : e.getMessage();
        return err(status, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return err(404, "not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("网关未处理异常", e);
        return err(500, e.getMessage() == null ? "internal error" : e.getMessage());
    }
}
