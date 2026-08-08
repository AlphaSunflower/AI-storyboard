package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关配置（gateway.*）：JWT/AES/上游超时 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {
    private Jwt jwt = new Jwt();
    private Aes aes = new Aes();
    private Upstream upstream = new Upstream();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Aes getAes() { return aes; }
    public void setAes(Aes aes) { this.aes = aes; }
    public Upstream getUpstream() { return upstream; }
    public void setUpstream(Upstream upstream) { this.upstream = upstream; }

    /** JWT 配置 */
    public static class Jwt {
        private String accessSecret;
        private String refreshSecret;
        private String issuer = "llm-gateway";
        private long accessTokenTtl = 3600;
        private long refreshTokenTtl = 2592000;

        public String getAccessSecret() { return accessSecret; }
        public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
        public String getRefreshSecret() { return refreshSecret; }
        public void setRefreshSecret(String refreshSecret) { this.refreshSecret = refreshSecret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public long getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(long accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
        public long getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(long refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    }

    /** AES 加密配置 */
    public static class Aes {
        private String secret;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    /** 上游渠道超时/重试配置 */
    public static class Upstream {
        private long connectTimeoutMs = 30000;
        private long requestTimeoutMs = 120000;
        private int retryCount = 2;

        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    }
}
