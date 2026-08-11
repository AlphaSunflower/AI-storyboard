package com.llmgateway.service;

/** 视频路由结果：上游 HTTP 状态码 + 响应体 */
public record VideoResult(int status, String body) {}
