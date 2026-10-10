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

    public static final String WRITE_FILE = "write_file";
    public static final String READ_FILE = "read_file";
    public static final String LIST_FILES = "list_files";
    public static final String RUN_PYTHON_SCRIPT = "run_python_script";

    private final MoonSandboxClient client;
    private final MoonSandboxClientProperties properties;
    private final AgentScopeAsyncTaskService asyncTasks;

    public List<AgentScopeTool> tools() {
        if (!properties.isEnabled() || !client.enabled()) return List.of();
        return List.of(writeFile(), readFile(), listFiles(), runPythonScript());
    }

    private AgentScopeTool writeFile() {
        return new AgentScopeTool(
                WRITE_FILE,
                "Write UTF-8 text to a file in the current conversation sandbox. "
                        + "Paths are relative to the session root, for example work/report.md. "
                        + "Binary files must be generated with run_python_script.",
                AgentScopeTool.schema(Map.of(
                        "path", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "mode", Map.of("type", "string",
                                "enum", List.of("CREATE", "OVERWRITE", "APPEND"))),
                        List.of("path", "content")),
                false, false, false,
                (input, param) -> client.write(
                        conversationId(param),
                        string(input.get("path")),
                        string(input.get("content")),
                        string(input.get("mode")).isBlank()
                                ? "OVERWRITE" : string(input.get("mode"))));
    }

    private AgentScopeTool readFile() {
        return new AgentScopeTool(
                READ_FILE,
                "Read a UTF-8 text file from the current conversation sandbox in chunks.",
                AgentScopeTool.schema(Map.of(
                        "path", Map.of("type", "string"),
                        "offset", Map.of("type", "integer"),
                        "maxChars", Map.of("type", "integer")),
                        List.of("path")),
                true, true, false,
                (input, param) -> client.read(
                        conversationId(param),
                        string(input.get("path")),
                        integer(input.get("offset"), 0),
                        integer(input.get("maxChars"), 0)));
    }

    private AgentScopeTool listFiles() {
        return new AgentScopeTool(
                LIST_FILES,
                "List files and directories in the current conversation sandbox.",
                AgentScopeTool.schema(Map.of(
                        "path", Map.of("type", "string")), List.of()),
                true, true, false,
                (input, param) -> client.list(
                        conversationId(param), string(input.get("path"))));
    }

    private AgentScopeTool runPythonScript() {
        return new AgentScopeTool(
                RUN_PYTHON_SCRIPT,
                "Execute a Python script asynchronously inside the current conversation sandbox. "
                        + "Files written to output/ are published to MinIO and returned as assets.",
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
