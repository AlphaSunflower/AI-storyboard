package com.moon.sandbox.web;

import com.moon.sandbox.session.SandboxPathException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class SandboxExceptionHandler {

    @ExceptionHandler(SandboxPathException.class)
    public ResponseEntity<Map<String, String>> handleSandboxError(SandboxPathException error) {
        HttpStatus status = error.getMessage() != null
                && error.getMessage().contains("full")
                ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of(
                "error", error.getMessage() == null ? "sandbox error" : error.getMessage()));
    }
}
