package com.storyboard.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.dto.response.ApiResponse;
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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** JSON 解析器（用于从上游嵌套 JSON 错误中提取可读信息） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从上游（Laozhang / Vertex AI 等）多层嵌套转义 JSON 错误中递归提取最内层可读 message。
     *
     * 例：Laozhang 404 错误会层层包裹成
     *   "AI 视频生成失败: Video API returned 404: {\"message\":\"{\\\"code\\\":\\\"fail_to_fetch_task\\\",...}\"}"
     * 提取后只剩最内层可读文本：
     *   "Publisher model `projects/.../veo-3.1-fast-generate-preview` was not found or ..."
     *
     * 非 JSON 消息（业务错误、普通异常）原样返回，不受影响。
     */
    private static String extractReadableError(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String current = raw.trim();
        // 最多剥 12 层，防止恶意/异常结构导致死循环
        for (int i = 0; i < 12; i++) {
            int start = current.indexOf('{');
            int end = current.lastIndexOf('}');
            if (start < 0 || end <= start) break; // 已无可提取的 JSON
            String jsonCandidate = current.substring(start, end + 1);
            try {
                JsonNode node = MAPPER.readTree(jsonCandidate);
                JsonNode msg = node.path("message");
                if (!msg.isTextual() || msg.asText().isBlank()) {
                    JsonNode err = node.path("error");
                    if (err.isObject() && err.path("message").isTextual()) {
                        msg = err.path("message");
                    }
                }
                if (msg.isTextual() && !msg.asText().isBlank()) {
                    String next = msg.asText().trim();
                    if (next.equals(current)) break; // 无进展，终止
                    current = next;
                } else {
                    // 是 JSON 但没有可读 message，返回精简后的 JSON 本身
                    return jsonCandidate.length() > 200 ? jsonCandidate.substring(0, 200) : jsonCandidate;
                }
            } catch (Exception e) {
                // JSON 解析失败，保留原始文本
                break;
            }
        }
        // 清理残留转义符（\n、\" 等），让单行展示更干净
        return current.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
    }

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

    /** 路径/查询参数类型不匹配（如非数值 page/size）（M1：统一返回 40001 业务错误码，替代 500） */
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
        String message = extractReadableError(e.getMessage());
        // 上游 AI 错误只记 message 不打堆栈（HttpClient 内部栈无意义）
        if (e.getCause() != null && message != null && !message.isBlank()) {
            log.error("Unhandled exception: {}", message);
        } else {
            log.error("Unhandled exception", e);
        }
        if (message == null || message.isBlank()) {
            message = "服务器内部错误";
        } else if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(50000, message));
    }
}
