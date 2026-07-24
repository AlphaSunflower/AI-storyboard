package com.storyboard.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.laozhang")
public class AiConfigProperties {
    private String apiKey;
    private String sora2OfficialApiKey;
    private String baseUrlOpenai = "https://api2.laozhang.ai/v1";
    private String baseUrlGemini = "https://api2.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent";
    private String baseUrlVision = "https://api2.laozhang.ai/v1/chat/completions";
    private String defaultImageModel = "gpt-image-2";
    private String defaultVisionModel = "gemini-3-flash-preview";
    private long pollIntervalMs = 5000;
    private long pollTimeoutMs = 600000;

    // getters and setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getSora2OfficialApiKey() { return sora2OfficialApiKey; }
    public void setSora2OfficialApiKey(String s) { this.sora2OfficialApiKey = s; }
    public String getBaseUrlOpenai() { return baseUrlOpenai; }
    public void setBaseUrlOpenai(String s) { this.baseUrlOpenai = s; }
    public String getBaseUrlGemini() { return baseUrlGemini; }
    public void setBaseUrlGemini(String s) { this.baseUrlGemini = s; }
    public String getBaseUrlVision() { return baseUrlVision; }
    public void setBaseUrlVision(String s) { this.baseUrlVision = s; }
    public String getDefaultImageModel() { return defaultImageModel; }
    public void setDefaultImageModel(String s) { this.defaultImageModel = s; }
    public String getDefaultVisionModel() { return defaultVisionModel; }
    public void setDefaultVisionModel(String s) { this.defaultVisionModel = s; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long l) { this.pollIntervalMs = l; }
    public long getPollTimeoutMs() { return pollTimeoutMs; }
    public void setPollTimeoutMs(long l) { this.pollTimeoutMs = l; }
}
