package com.moon.apigateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 请求包装器 — 追加自定义 header（X-User-Id 等）传给下游服务 */
public class HeaderWrapper extends HttpServletRequestWrapper {

    private final Map<String, String> extraHeaders;

    public HeaderWrapper(HttpServletRequest request, Map<String, String> extraHeaders) {
        super(request);
        this.extraHeaders = extraHeaders;
    }

    @Override
    public String getHeader(String name) {
        String value = extraHeaders.get(name);
        return value != null ? value : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = extraHeaders.get(name);
        if (value != null) {
            return Collections.enumeration(List.of(value));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Map<String, String> all = new HashMap<>();
        // 原始 header
        Enumeration<String> original = super.getHeaderNames();
        while (original.hasMoreElements()) {
            String name = original.nextElement();
            all.put(name, super.getHeader(name));
        }
        // 追加 header
        all.putAll(extraHeaders);
        return Collections.enumeration(all.keySet());
    }
}
