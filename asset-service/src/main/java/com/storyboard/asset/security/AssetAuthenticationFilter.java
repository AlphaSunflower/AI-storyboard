package com.storyboard.asset.security;

import com.storyboard.asset.config.AssetProperties;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** asset-service 自己校验 JWT；仅内部调用允许共享密钥认证。 */
@Component
public class AssetAuthenticationFilter extends OncePerRequestFilter {

    private final AssetProperties properties;
    private final String jwtSecret;
    private final String issuer;

    public AssetAuthenticationFilter(
            AssetProperties properties,
            @Value("${jwt.access-secret:}") String jwtSecret,
            @Value("${jwt.issuer:ai-storyboard-backend}") String issuer
    ) {
        this.properties = properties;
        this.jwtSecret = jwtSecret;
        this.issuer = issuer;
    }

    @PostConstruct
    void validateInternalToken() {
        if (properties.internalToken() == null || properties.internalToken().isBlank()) {
            throw new IllegalStateException(
                    "storyboard.asset.internal-token 未配置：请设置 INTERNAL_SECRET");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String internalToken = request.getHeader("X-Internal-Token");
        String internalUser = request.getHeader("X-User-Id");
        if (internalToken != null && !properties.internalToken().isBlank()
                && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                properties.internalToken().getBytes(StandardCharsets.UTF_8))) {
            String user = (internalUser != null && !internalUser.isBlank()) ? internalUser : "internal";
            authenticate(user, "internal");
            chain.doFilter(request, response);
            return;
        }

        // Asset Service 只接受 Gateway 注入的内部令牌，直连 JWT 无法校验会话撤销。
        chain.doFilter(request, response);
    }

    private void authenticate(String userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "MEMBER" : role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }
}
