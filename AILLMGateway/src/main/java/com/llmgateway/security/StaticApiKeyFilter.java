package com.llmgateway.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.service.KeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** /v1/** 静态 API Key 校验：Bearer → SHA-256 → gateway_api_key 表比对 */
@Component
public class StaticApiKeyFilter extends OncePerRequestFilter {

    private final GatewayApiKeyMapper apiKeyMapper;
    private final KeyService keyService;

    public StaticApiKeyFilter(GatewayApiKeyMapper apiKeyMapper, KeyService keyService) {
        this.apiKeyMapper = apiKeyMapper;
        this.keyService = keyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "missing api key");
            return;
        }
        String key = auth.substring(7);
        GatewayApiKey record = apiKeyMapper.selectOne(new LambdaQueryWrapper<GatewayApiKey>()
                .eq(GatewayApiKey::getKeyHash, keyService.sha256(key))
                .eq(GatewayApiKey::getEnabled, true));
        if (record == null) {
            writeUnauthorized(response, "invalid api key");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("gateway", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }
}
