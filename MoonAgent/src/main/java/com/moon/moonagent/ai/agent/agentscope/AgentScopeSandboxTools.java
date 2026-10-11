package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClientProperties;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeSandboxTools {

    public static final String RUN_PYTHON_SCRIPT = "run_python_script";

    private final MoonSandboxClient client;
    private final MoonSandboxClientProperties properties;
    private final AgentScopeAsyncTaskService asyncTasks;

    public List<AgentScopeTool> tools() {
        if (!properties.isEnabled() || !client.enabled()) return List.of();
        return List.of(runPythonScript());
    }

    private AgentScopeTool runPythonScript() {
        return new AgentScopeTool(
                RUN_PYTHON_SCRIPT,
                "Execute a Python script asynchronously inside the current conversation sandbox. "
                        + "All user-facing deliverables MUST be written beneath output/; only files "
                        + "under output/ are published and returned as clickable attachments.",
                AgentScopeTool.schema(Map.of(
                        "script", Map.of("type", "string"),
                        "args", Map.of("type", "array",
                                "items", Map.of("type", "string")),
                        "timeoutSeconds", Map.of("type", "integer")),
                        List.of("script")),
                false, false, false,
                (input, param) -> {
                    try {
                        String taskId = asyncTasks.startSandboxScriptTask(
                                AgentScopeSessionTools.requireConversation(context(param)),
                                string(input.get("script")),
                                stringList(input.get("args")),
                                integer(input.get("timeoutSeconds"), 300),
                                param == null || param.getToolUseBlock() == null
                                        ? "" : param.getToolUseBlock().getId());
                        return Map.of("ok", true, "taskId", taskId, "status", "queued");
                    } catch (MoonSandboxException error) {
                        return Map.of("ok", false, "error",
                                error.getMessage() == null ? "sandbox error" : error.getMessage());
                    }
                });
    }

    private static String conversationId(io.agentscope.core.tool.ToolCallParam param) {
        return AgentScopeSessionTools.requireConversation(context(param)).getId();
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }
}
