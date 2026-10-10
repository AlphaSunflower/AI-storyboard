package com.moon.moonagent.ai.agent.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.documents")
public class DocumentProperties {

    private boolean enabled = true;
    private int parseTimeoutSeconds = 45;
    private int summaryTimeoutSeconds = 60;
    private int maxParseChars = 1_000_000;
    private int defaultReadChars = 8_000;
    private int maxReadChars = 12_000;
    private int summaryChunkChars = 12_000;
    private int summaryMaxChunks = 24;
    private int summaryConcurrency = 3;
    private int summaryMaxChars = 1_500;
    private int cacheMaxBytes = 64 * 1024 * 1024;
    private int cacheExpireMinutes = 30;
    private int recentSummaryLimit = 5;
    private int recentSummaryMaxChars = 6_000;
    private String summaryModel = "deepseek-flash";
}
