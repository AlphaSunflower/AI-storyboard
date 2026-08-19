package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关配置（gateway.*）：JWT/AES/上游超时/视频默认档 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {
    private Jwt jwt = new Jwt();
    private Aes aes = new Aes();
    private Upstream upstream = new Upstream();
    private Video video = new Video();
    /** 首启管理员自举密码（gateway.admin-init-password；仅 admin_user 表为空时生效） */
    private String adminInitPassword;

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Aes getAes() { return aes; }
    public void setAes(Aes aes) { this.aes = aes; }
    public Upstream getUpstream() { return upstream; }
    public void setUpstream(Upstream upstream) { this.upstream = upstream; }
    public Video getVideo() { return video; }
    public void setVideo(Video video) { this.video = video; }

    /** 视频默认档便捷访问（对应 gateway.video.default-resolution） */
    public String getVideoDefaultResolution() { return video.getDefaultResolution(); }

    public String getAdminInitPassword() { return adminInitPassword; }
    public void setAdminInitPassword(String adminInitPassword) { this.adminInitPassword = adminInitPassword; }

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
        private long requestTimeoutMs = 300000;
        private int retryCount = 2;

        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    }

    /** 视频默认档配置（gateway.video.*） */
    public static class Video {
        private String defaultResolution = "768P";   // MiniMax 默认档（省钱）
        private String defaultDuration = "8";

        public String getDefaultResolution() { return defaultResolution; }
        public void setDefaultResolution(String defaultResolution) { this.defaultResolution = defaultResolution; }
        public String getDefaultDuration() { return defaultDuration; }
        public void setDefaultDuration(String defaultDuration) { this.defaultDuration = defaultDuration; }
    }
}
