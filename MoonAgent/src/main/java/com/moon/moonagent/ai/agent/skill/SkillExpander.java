package com.moon.moonagent.ai.agent.skill;

import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.audit.AuditedToolCallback;
import com.moon.moonagent.ai.agent.audit.ToolExecutionTemplate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具/技能桥 —— 声明即扩展入口。
 *
 * <p>职责：把 {@link AgentTools} 上的 {@code @Tool}/{@code @McpTool} 方法提取为
 * {@link ToolCallback}，按工具名建立索引，供 AgentScope Toolkit 注册。
 *
 * <p>设计定案：加能力 = 加一个 {@code SKILL.md} + 加/改一个 {@code @Tool}，编排零改动；
 * 不引入 LLM 自动发明技能（不做运行时合成）。
 */
@Component
@RequiredArgsConstructor
public class SkillExpander implements ToolAvailability {

    private static final Logger log = LoggerFactory.getLogger(SkillExpander.class);

    private final AgentTools agentTools;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ToolExecutionTemplate toolExecutionTemplate;

    /** 工具名 → ToolCallback（启动时由 AgentTools 扫描生成） */
    private volatile Map<String, ToolCallback> toolByName = Map.of();

    @PostConstruct
    void init() {
        ToolCallback[] localCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(agentTools)
                .build()
                .getToolCallbacks();
        Map<String, ToolCallback> map = new HashMap<>();
        for (ToolCallback callback : localCallbacks) {
            String name = callback.getToolDefinition().name();
            map.put(name, callback);
            log.info("[SkillExpander] 发现本地工具: {}", name);
        }
        toolCallbackProviders.orderedStream().forEach(provider -> {
            try {
                for (ToolCallback callback : provider.getToolCallbacks()) {
                    String name = callback.getToolDefinition().name();
                    ToolCallback previous = map.putIfAbsent(
                            name, new AuditedToolCallback(callback, toolExecutionTemplate));
                    if (previous == null) log.info("[SkillExpander] 发现 MCP 工具: {}", name);
                }
            } catch (Exception e) {
                log.warn("[SkillExpander] MCP 工具提供器不可用: {}", e.getMessage());
            }
        });
        toolByName = map;

        log.info("[SkillExpander] 共索引 {} 个工具: {}", toolByName.size(), toolByName.keySet());
    }

    /** 全部可用工具名 */
    public Set<String> allToolNames() {
        return toolByName.keySet();
    }

    @Override
    public boolean hasTool(String name) {
        return name != null && toolByName.containsKey(name);
    }

    /** MCP Client 延迟连接成功后，把新发现的远程工具合并进技能工具表。 */
    public synchronized void registerRemoteCallbacks(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) return;
        Map<String, ToolCallback> merged = new HashMap<>(toolByName);
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            ToolCallback previous = merged.putIfAbsent(name, callback);
            if (previous == null) log.info("[SkillExpander] 延迟发现 MCP 工具: {}", name);
        }
        toolByName = Map.copyOf(merged);
    }

    /**
     * 按名称过滤出技能声明所需的 {@link ToolCallback}。
     * 未命中（工具缺失/未声明）返回空数组，调用方据此降级。
     */
    public ToolCallback[] toolCallbacks(List<String> names) {
        if (names == null || names.isEmpty()) return new ToolCallback[0];
        List<ToolCallback> out = new ArrayList<>();
        for (String name : names) {
            ToolCallback callback = name == null ? null : toolByName.get(name);
            if (callback != null) out.add(callback);
        }
        return out.toArray(new ToolCallback[0]);
    }

    /** Return callbacks whose tool name starts with the supplied prefix. */
    public ToolCallback[] toolCallbacksByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return new ToolCallback[0];
        return toolByName.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toArray(ToolCallback[]::new);
    }
}
