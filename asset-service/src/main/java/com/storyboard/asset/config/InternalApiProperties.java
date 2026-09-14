package com.storyboard.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storyboard.internal")
public record InternalApiProperties(String projectUrl, String secret) {
}
