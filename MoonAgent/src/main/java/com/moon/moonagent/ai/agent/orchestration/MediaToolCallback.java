package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.orchestration.MediaToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class MediaToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final MediaGenerationCoordinator coordinator;
    private final AgentConversation conversation;
    private final Consumer<MediaToolResult> resultConsumer;
    private final String turnKey = UUID.randomUUID().toString();
    private final ConcurrentMap<String, String> keyByInput = new ConcurrentHashMap<>();

    public MediaToolCallback(ToolCallback delegate, MediaGenerationCoordinator coordinator,
                             AgentConversation conversation) {
        this(delegate, coordinator, conversation, ignored -> { });
    }

    public MediaToolCallback(ToolCallback delegate, MediaGenerationCoordinator coordinator,
                             AgentConversation conversation, Consumer<MediaToolResult> resultConsumer) {
        this.delegate = delegate;
        this.coordinator = coordinator;
        this.conversation = conversation;
        this.resultConsumer = resultConsumer == null ? ignored -> { } : resultConsumer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String key = stableKey(getToolDefinition().name(), toolInput);
        MediaGenerationCoordinator.Prepared prepared = coordinator.prepare(
                conversation, getToolDefinition().name(), toolInput, key);
        try {
            String result = delegate.call(prepared.toolInput());
            MediaToolResult parsed = coordinator.complete(prepared, result);
            resultConsumer.accept(parsed);
            return result;
        } catch (RuntimeException e) {
            coordinator.fail(prepared, e);
            throw e;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String key = stableKey(getToolDefinition().name(), toolInput);
        MediaGenerationCoordinator.Prepared prepared = coordinator.prepare(
                conversation, getToolDefinition().name(), toolInput, key);
        try {
            String result = delegate.call(prepared.toolInput(), toolContext);
            MediaToolResult parsed = coordinator.complete(prepared, result);
            resultConsumer.accept(parsed);
            return result;
        } catch (RuntimeException e) {
            coordinator.fail(prepared, e);
            throw e;
        }
    }

    private String stableKey(String toolName, String toolInput) {
        String inputKey = toolName + "|" + (toolInput == null ? "" : toolInput);
        return keyByInput.computeIfAbsent(inputKey, ignored -> {
            int hash = java.util.Arrays.hashCode(inputKey.getBytes(StandardCharsets.UTF_8));
            return turnKey + ":" + toolName + ":" + Integer.toUnsignedString(hash, 16);
        });
    }
}
