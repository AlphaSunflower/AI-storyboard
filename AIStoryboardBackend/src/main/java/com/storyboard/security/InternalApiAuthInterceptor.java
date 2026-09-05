package com.storyboard.security;

import com.storyboard.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部 API 统一鉴权拦截器 — 校验 X-Internal-Token 头部与共享密钥。
 * <p>单一鉴权点：新增 /api/internal/** 端点无需再逐方法校验，避免漏校验。密钥未配置时启动即失败，
 * 不允许回退到仓库内默认值（防止硬编码密钥对外暴露）。
 */
@Component
public class InternalApiAuthInterceptor implements HandlerInterceptor {

    private final byte[] expected;

    public InternalApiAuthInterceptor(@Value("${internal.secret:}") String internalSecret) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException(
                    "internal.secret 未配置：请设置环境变量 INTERNAL_SECRET（或 application-local.yml 中的 internal.secret）");
        }
        // 归一化到字节数组供常量时间比较，避免每次请求重复编码
        this.expected = internalSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("X-Internal-Token");
        // MessageDigest.isEqual 做常量时间比较，避免时序侧信道
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected)) {
            throw new BusinessException(40301, "内部接口鉴权失败");
        }
        return true;
    }
}
