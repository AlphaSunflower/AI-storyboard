package com.storyboard.asset.security;

import com.storyboard.asset.config.AssetProperties;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String internalToken = request.getHeader("X-Internal-Token");
        String internalUser = request.getHeader("X-User-Id");
        if (internalToken != null && !properties.internalToken().isBlank()
                && internalToken.equals(properties.internalToken())) {
            String user = (internalUser != null && !internalUser.isBlank()) ? internalUser : "internal";
            authenticate(user, "internal");
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && !jwtSecret.isBlank()) {
            try {
                String token = authorization.substring(7);
                byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
                javax.crypto.SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
                var claims = Jwts.parser()
                        .verifyWith(key)
                        .requireIssuer(issuer)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                authenticate(claims.getSubject(), claims.get("role", String.class));
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "MEMBER" : role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }
}
