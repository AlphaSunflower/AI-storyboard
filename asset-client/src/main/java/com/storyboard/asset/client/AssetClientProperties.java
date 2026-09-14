package com.storyboard.asset.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** asset-client 统一连接参数。 */
@ConfigurationProperties(prefix = "storyboard.asset-client")
public record AssetClientProperties(
        String baseUrl,
        String internalToken,
        int connectTimeoutSeconds,
        int readTimeoutSeconds
) {
    public AssetClientProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8085" : baseUrl.trim();
        internalToken = internalToken == null ? "" : internalToken.trim();
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 10 : connectTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds <= 0 ? 30 : readTimeoutSeconds;
    }
}
