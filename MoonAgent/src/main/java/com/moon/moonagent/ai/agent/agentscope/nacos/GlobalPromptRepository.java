package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.moon.moonagent.config.PromptResolver;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reads the versioned global system prompt from Nacos with a classpath fallback.
 */
public class GlobalPromptRepository {

    private static final Logger log = LoggerFactory.getLogger(GlobalPromptRepository.class);

    private final NacosAssetProperties properties;
    private final Supplier<AiService> aiServiceSupplier;
    private final Resource classpathFallback;

    private volatile NacosPromptListener listener;
    private volatile String selectedSource = "unresolved";

    public GlobalPromptRepository(
            NacosAssetProperties properties,
            Supplier<AiService> aiServiceSupplier,
            Resource classpathFallback) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.aiServiceSupplier = Objects.requireNonNull(aiServiceSupplier, "aiServiceSupplier");
        this.classpathFallback = Objects.requireNonNull(classpathFallback, "classpathFallback");
    }

    public String getPrompt(Map<String, String> variables) {
        if (properties.isEnabled()) {
            try {
                AiService aiService = aiServiceSupplier.get();
                if (aiService != null) {
                    String version = blankToNull(properties.getPromptVersion());
                    String label = version == null ? blankToNull(properties.getPromptLabel()) : null;
                    String prompt = listener(aiService).getPrompt(
                            properties.getPromptKey(),
                            version,
                            label,
                            variables,
                            null);
                    if (prompt != null && !prompt.isBlank()) {
                        selectedSource = "nacos";
                        return prompt;
                    }
                }
            } catch (Exception exception) {
                if (!properties.isAllowClasspathFallback()) {
                    throw new IllegalStateException(
                            "Nacos global prompt unavailable and classpath fallback is disabled",
                            exception);
                }
                log.warn("[NacosAssets] global prompt Nacos lookup failed, using classpath: {}",
                        rootMessage(exception));
            }
        }

        if (!properties.isAllowClasspathFallback()) {
            throw new IllegalStateException(
                    "Nacos global prompt unavailable and classpath fallback is disabled");
        }
        selectedSource = "classpath";
        return PromptResolver.resolve(readFallback(), variables);
    }

    public String selectedSource() {
        return selectedSource;
    }

    public void refresh() {
        selectedSource = "unresolved";
        listener = null;
    }

    private NacosPromptListener listener(AiService aiService) {
        NacosPromptListener current = listener;
        if (current == null) {
            synchronized (this) {
                if (listener == null) {
                    listener = new NacosPromptListener(aiService);
                }
                current = listener;
            }
        }
        return current;
    }

    private String readFallback() {
        try (var input = classpathFallback.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to read classpath global prompt: " + classpathFallback, exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
