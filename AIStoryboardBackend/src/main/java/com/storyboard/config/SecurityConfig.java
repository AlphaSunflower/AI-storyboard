package com.storyboard.config;

import com.storyboard.config.AccessLogFilter;
import com.storyboard.security.GatewayAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayAuthenticationFilter gatewayFilter;
    private final AccessLogFilter accessLogFilter;

    public SecurityConfig(GatewayAuthenticationFilter gatewayFilter, AccessLogFilter accessLogFilter) {
        this.gatewayFilter = gatewayFilter;
        this.accessLogFilter = accessLogFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        // 允许 PATCH：前端重命名/归档会话走 PATCH /api/agent/conversations/{id}，预检需放行
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // SseEmitter 异步完成时 Tomcat 会 asyncDispatch 二次经过过滤器链，
                // 此时 SecurityContext 已丢失（anonymous）→ 授权拒绝会破坏已提交的 SSE 响应。
                // 仅对 ASYNC/ERROR 分发放行，REQUEST 分发仍走 JWT 保护（安全不降级）。
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/files/**").permitAll()
                .requestMatchers("/api/internal/**").permitAll()
                // Swagger UI / OpenAPI 文档（本地联调用；prod 由 springdoc.enabled=false 关闭）
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/webjars/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()  // 健康检查（部署探活，只暴露 health）
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(401);
                    response.getWriter().write("{\"code\":40101,\"message\":\"未授权，请先登录\"}");
                })
            )
            .addFilterBefore(gatewayFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(accessLogFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
