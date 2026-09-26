package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.dto.response.ToolInvocationView;
import com.moon.moonagent.entity.AgentToolInvocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolInvocationQueryService {

    private final ToolInvocationLedger ledger;
    private final ToolAuditProperties properties;

    public Map<String, Object> list(String userId,
                                    String taskId,
                                    String conversationId,
                                    String cursor,
                                    int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<AgentToolInvocation> rows = ledger.list(
                userId, taskId, conversationId, cursor, safeLimit);
        boolean hasMore = rows.size() > safeLimit;
        List<AgentToolInvocation> visible = hasMore ? rows.subList(0, safeLimit) : rows;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", visible.stream().map(ToolInvocationView::from).toList());
        result.put("nextCursor", hasMore && !visible.isEmpty()
                ? encode(visible.getLast()) : null);
        result.put("limit", safeLimit);
        return result;
    }

    public ToolInvocationView detail(String userId, String invocationId) {
        return ToolInvocationView.from(ledger.owned(invocationId, userId));
    }

    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "uiEnabled", properties.isUiEnabled(),
                "retentionDays", properties.getRetentionDays());
    }

    private static String encode(AgentToolInvocation invocation) {
        OffsetDateTime startedAt = invocation.getStartedAt() == null
                ? OffsetDateTime.now() : invocation.getStartedAt();
        String raw = startedAt + "|" + invocation.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
