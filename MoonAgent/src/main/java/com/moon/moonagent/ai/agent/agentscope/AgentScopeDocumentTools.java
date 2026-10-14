package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.DocumentParseException;
import com.moon.moonagent.ai.agent.document.DocumentProperties;
import com.moon.moonagent.ai.agent.document.DocumentReadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeDocumentTools {

    public static final String LIST_CONVERSATION_DOCUMENTS = "list_conversation_documents";
    public static final String READ_DOCUMENT = "read_document";

    private final AgentDocumentService documents;
    private final DocumentProperties properties;

    public List<AgentScopeTool> tools() {
        if (!enabled()) return List.of();
        return List.of(listDocuments(), readDocument());
    }

    /** 文档解析是否启用；能力声明块据此判断 {@code read_document} 是否真的可用。 */
    public boolean enabled() {
        return properties.isEnabled();
    }

    private AgentScopeTool listDocuments() {
        return new AgentScopeTool(
                LIST_CONVERSATION_DOCUMENTS,
                "List uploaded documents in the current conversation with their persisted summaries. "
                        + "Use summaries for general follow-up questions and read_document for exact details.",
                AgentScopeTool.schema(Map.of(
                        "limit", Map.of("type", "integer")), List.of()),
                true, true, false,
                (input, param) -> documents.recentSummaries(
                        AgentScopeSessionTools.requireConversation(context(param)).getId(),
                        integer(input.get("limit"), 5)));
    }

    private AgentScopeTool readDocument() {
        return new AgentScopeTool(
                READ_DOCUMENT,
                "Read a Markdown-converted chunk from an uploaded Word, Excel, PowerPoint, PDF, "
                        + "Markdown, or common text document in the current conversation. Use assetId from the "
                        + "current attachment metadata. Increase offset using nextOffset when needed.",
                AgentScopeTool.schema(Map.of(
                        "assetId", Map.of("type", "string"),
                        "offset", Map.of("type", "integer"),
                        "maxChars", Map.of("type", "integer")), List.of("assetId")),
                true, true, false,
                (input, param) -> {
                    String conversationId = AgentScopeSessionTools
                            .requireConversation(context(param)).getId();
                    try {
                        DocumentReadResult result = documents.read(
                                conversationId,
                                string(input.get("assetId")),
                                integer(input.get("offset"), 0),
                                integer(input.get("maxChars"), 0));
                        Map<String, Object> output = new LinkedHashMap<>();
                        output.put("ok", true);
                        output.put("assetId", result.assetId());
                        output.put("name", result.name());
                        output.put("mimeType", result.mimeType());
                        output.put("summary", result.summary());
                        output.put("status", result.status());
                        output.put("offset", result.offset());
                        output.put("nextOffset", result.nextOffset());
                        output.put("totalChars", result.totalChars());
                        output.put("truncated", result.truncated());
                        output.put("markdown", result.markdown());
                        return output;
                    } catch (DocumentParseException error) {
                        return Map.of(
                                "ok", false,
                                "status", error.code(),
                                "assetId", string(input.get("assetId")),
                                "message", error.getMessage());
                    }
                });
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
}
