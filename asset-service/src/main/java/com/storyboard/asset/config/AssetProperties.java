package com.storyboard.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storyboard.asset")
public record AssetProperties(
        String internalToken,
        String gatewayBaseUrl,
        String gatewayApiKey,
        String signingSecret
) {
}
