package com.llmgateway.config;

import com.llmgateway.security.AdminJwtFilter;
import com.llmgateway.security.StaticApiKeyFilter;
import jakarta.servlet.DispatcherType;
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

import java.util.List;

/** 安全配置：/v1/** 静态 Key（StaticApiKeyFilter 自校验）+ /admin/** JWT */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AdminJwtFilter adminJwtFilter;
    private final StaticApiKeyFilter staticApiKeyFilter;

    public SecurityConfig(AdminJwtFilter adminJwtFilter, StaticApiKeyFilter staticApiKeyFilter) {
        this.adminJwtFilter = adminJwtFilter;
        this.staticApiKeyFilter = staticApiKeyFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()  // SseEmitter/异步必需
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/admin/login").permitAll()
                .requestMatchers("/admin-ui/**").permitAll()  // 管理后台静态页放行：AdminJwtFilter.shouldNotFilter 只对 /admin/ 前缀生效，/admin-ui/ 前缀天然不受拦截，安全
                .requestMatchers("/v1/**").permitAll()           // 静态 Key 由 StaticApiKeyFilter 自校验
                .requestMatchers("/admin/**").hasRole("ADMIN")  // JWT 过滤器设置 ROLE_ADMIN
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":{\"message\":\"unauthorized\"}}");
                })
            )
            .addFilterBefore(staticApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
