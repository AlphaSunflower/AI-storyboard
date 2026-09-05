package com.storyboard.config;

import com.storyboard.security.InternalApiAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部 API 拦截器注册：仅作用于 /api/internal/**，前端经网关访问该前缀已被拒绝，此处为纵深防御。
 */
@Configuration
@RequiredArgsConstructor
public class InternalApiWebConfig implements WebMvcConfigurer {

    private final InternalApiAuthInterceptor internalApiAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor).addPathPatterns("/api/internal/**");
    }
}
