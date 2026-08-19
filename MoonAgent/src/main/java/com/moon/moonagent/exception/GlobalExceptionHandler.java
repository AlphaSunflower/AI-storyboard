package com.moon.moonagent.exception;

import com.moon.moonagent.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;

/** 全局异常处理：BusinessException → 对应 HTTP 状态码 JSON；客户端断连静默；其余 → 500 */
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
            case 40901 -> HttpStatus.CONFLICT;
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

    /** 上传文件超过大小上限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "文件过大或缺少文件参数"));
    }

    /** multipart 缺少文件参数 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "文件过大或缺少文件参数"));
    }

    /** 路径/查询参数类型不匹配（如非数值 page/size） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "参数类型错误：" + e.getName()));
    }

    /**
     * 客户端断开连接（前端刷新/取消/超时中断 SSE 流式请求）：响应流已死，写不进任何数据。
     * 非业务错误——静默吞掉，避免 handleUnknown 刷 ERROR 且写响应再抛一次同样的错。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientAbort(IOException e) {
        log.debug("客户端中断请求（连接已断开）: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        String message = e.getMessage();
        log.error("Unhandled exception: {}", message != null && !message.isBlank() ? message : e.toString());
        if (message == null || message.isBlank()) {
            message = "服务器内部错误";
        } else if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(50000, message));
    }
}
