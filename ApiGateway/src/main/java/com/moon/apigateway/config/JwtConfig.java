package com.moon.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 配置（与主后端共享 secret，统一验签） */
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {
    private String accessSecret;
    private String issuer = "newworkflow-backend";

    public String getAccessSecret() { return accessSecret; }
    public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
