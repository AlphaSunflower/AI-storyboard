package com.llmgateway.service;

/** 路由结果：上游 HTTP 状态码 + 响应体 */
public record RouteResult(int status, String body) {}
