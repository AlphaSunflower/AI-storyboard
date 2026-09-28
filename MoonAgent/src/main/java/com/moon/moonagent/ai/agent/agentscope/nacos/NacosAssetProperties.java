package com.moon.moonagent.ai.agent.agentscope.nacos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Versioned AgentScope assets hosted by Nacos.
 *
 * <p>The classpath remains the fallback. When Nacos is disabled or unavailable,
 * no runtime bootstrap step may depend on it.
 */
@Component
@ConfigurationProperties(prefix = "ai.agent.agentscope.nacos")
public class NacosAssetProperties {

    private boolean enabled;
    private String serverAddress = "localhost:8848";
    private String namespace = "public";
    private String username = "";
    private String password = "";
    private String skillVersion = "";
    private String skillLabel = "stable";
    private String promptKey = "moon-agent-global-system";
    private String promptVersion = "";
    private String promptLabel = "stable";
    private String skillFallbackLocation = "skills";
    private String globalPromptFallbackLocation = "agent-assets/global-system-prompt.txt";
    private String mcpMetadataFallbackLocation = "agent-assets/mcp-servers.json";
    private List<String> skillNames = List.of(
            "image", "video", "storyboard", "canvas-design", "canvas-run", "general");
    private boolean allowClasspathFallback = true;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = blankToDefault(serverAddress, "localhost:8848");
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = blankToDefault(namespace, "public");
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getSkillVersion() {
        return skillVersion;
    }

    public void setSkillVersion(String skillVersion) {
        this.skillVersion = trimToEmpty(skillVersion);
    }

    public String getSkillLabel() {
        return skillLabel;
    }

    public void setSkillLabel(String skillLabel) {
        this.skillLabel = trimToEmpty(skillLabel);
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = blankToDefault(promptKey, "moon-agent-global-system");
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = trimToEmpty(promptVersion);
    }

    public String getPromptLabel() {
        return promptLabel;
    }

    public void setPromptLabel(String promptLabel) {
        this.promptLabel = trimToEmpty(promptLabel);
    }

    public String getSkillFallbackLocation() {
        return skillFallbackLocation;
    }

    public void setSkillFallbackLocation(String skillFallbackLocation) {
        this.skillFallbackLocation = blankToDefault(skillFallbackLocation, "skills");
    }

    public String getGlobalPromptFallbackLocation() {
        return globalPromptFallbackLocation;
    }

    public void setGlobalPromptFallbackLocation(String globalPromptFallbackLocation) {
        this.globalPromptFallbackLocation =
                blankToDefault(globalPromptFallbackLocation, "agent-assets/global-system-prompt.txt");
    }

    public String getMcpMetadataFallbackLocation() {
        return mcpMetadataFallbackLocation;
    }

    public void setMcpMetadataFallbackLocation(String mcpMetadataFallbackLocation) {
        this.mcpMetadataFallbackLocation =
                blankToDefault(mcpMetadataFallbackLocation, "agent-assets/mcp-servers.json");
    }

    public List<String> getSkillNames() {
        return skillNames;
    }

    public void setSkillNames(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            this.skillNames = List.of();
            return;
        }
        this.skillNames = skillNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isAllowClasspathFallback() {
        return allowClasspathFallback;
    }

    public void setAllowClasspathFallback(boolean allowClasspathFallback) {
        this.allowClasspathFallback = allowClasspathFallback;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(2));
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = positiveOrDefault(requestTimeout, Duration.ofSeconds(5));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
