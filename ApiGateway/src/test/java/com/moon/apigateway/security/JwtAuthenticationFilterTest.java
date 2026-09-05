package com.moon.apigateway.security;

import com.moon.apigateway.config.JwtConfig;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "d02f1779750841f8b4de65e47a913e89";
    private static final String ISSUER = "newworkflow-backend";

    private MockMvc mockMvc;
    private SecretKey key;

    @RestController
    static class Downstream {
        @GetMapping("/api/auth/health")
        String health() {
            return "ok";
        }

        @GetMapping("/echo")
        Map<String, String> echo(HttpServletRequest req) {
            return Map.of("uid", req.getHeader("X-User-Id"));
        }
    }

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setAccessSecret(SECRET);
        config.setIssuer(ISSUER);
        JwtVerifier verifier = new JwtVerifier(config);
        key = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");
        mockMvc = MockMvcBuilders.standaloneSetup(new Downstream())
            .addFilters(new JwtAuthenticationFilter(verifier))
            .build();
    }

    private String accessToken(String subject) {
        return Jwts.builder().subject(subject).issuer(ISSUER)
            .claim("typ", "access").claim("role", "member").claim("status", "enabled")
            .signWith(key).compact();
    }

    @Test
    void whitelistPaths_passThroughWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/health"))
            .andExpect(status().isOk());
    }

    @Test
    void internalPaths_areForbidden() throws Exception {
        mockMvc.perform(get("/api/internal/projects/x"))
            .andExpect(status().isForbidden());
    }

    @Test
    void missingToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/echo"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/echo").header("Authorization", "Bearer bad"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void validToken_injectsUserHeaders() throws Exception {
        mockMvc.perform(get("/echo").header("Authorization", "Bearer " + accessToken("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uid").value("u1"));
    }
}
