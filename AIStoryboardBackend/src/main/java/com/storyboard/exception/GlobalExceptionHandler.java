package com.storyboard.exception;

import com.storyboard.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = switch (e.getCode()) {
            case 40101, 40102 -> HttpStatus.UNAUTHORIZED;
            case 40301 -> HttpStatus.FORBIDDEN;
            case 40401 -> HttpStatus.NOT_FOUND;
            case 40001 -> HttpStatus.BAD_REQUEST;
            case 50201, 50202 -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("参数错误");
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, msg));
    }

    /** 上传文件超过大小上限（I2：统一返回 40001 业务错误码） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "文件过大或缺少文件参数"));
    }

    /** multipart 缺少文件参数（I2：统一返回 40001 业务错误码） */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "文件过大或缺少文件参数"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "服务器内部错误";
        } else if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(50000, message));
    }
}
