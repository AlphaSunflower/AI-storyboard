package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.entity.AgentExternalAction;
import com.moon.moonagent.mapper.AgentExternalActionMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalActionLedgerServiceTest {

    @Test
    void firstInvocationWritesSentAndAcceptedReceipt() {
        AgentExternalActionMapper mapper = mock(AgentExternalActionMapper.class);
        when(mapper.selectByActionKey(anyString())).thenReturn(null);
        when(mapper.insert(any(AgentExternalAction.class))).thenReturn(1);
        when(mapper.markSent(anyString())).thenReturn(1);
        ExternalActionLedgerService service = new ExternalActionLedgerService(mapper);
        AtomicInteger calls = new AtomicInteger();

        Map<String, Object> result = service.executeOnce(
                "c1", "cp1", "call1", "generate_video", "media-video",
                Map.of("prompt", "测试"),
                () -> {
                    calls.incrementAndGet();
                    return Map.of("taskId", "task-1");
                });

        assertThat(result).containsEntry("taskId", "task-1");
        assertThat(calls).hasValue(1);
        verify(mapper).finish(eq("cp1:call1:generate_video"), eq("ACCEPTED"),
                eq("task-1"), anyString(), eq(null));
    }

    @Test
    void acceptedReceiptIsReplayedWithoutCallingProviderAgain() {
        AgentExternalActionMapper mapper = mock(AgentExternalActionMapper.class);
        AgentExternalAction existing = new AgentExternalAction();
        existing.setActionKey("cp1:call1:generate_image");
        existing.setStatus("COMPLETED");
        existing.setResponseJson("{\"imageUrls\":[\"/result.png\"],\"assetIds\":[\"a1\"]}");
        when(mapper.selectByActionKey("cp1:call1:generate_image")).thenReturn(existing);
        ExternalActionLedgerService service = new ExternalActionLedgerService(mapper);
        AtomicInteger calls = new AtomicInteger();

        Map<String, Object> result = service.executeOnce(
                "c1", "cp1", "call1", "generate_image", "media-image",
                Map.of("prompt", "测试"),
                () -> {
                    calls.incrementAndGet();
                    return Map.of("imageUrls", java.util.List.of("/other.png"));
                });

        assertThat(calls).hasValue(0);
        assertThat(result)
                .containsEntry("replayed", true)
                .containsEntry("externalActionKey", "cp1:call1:generate_image");
        assertThat(result.get("imageUrls")).isEqualTo(java.util.List.of("/result.png"));
        verify(mapper, never()).markSent(anyString());
    }

    @Test
    void providerExceptionBecomesUnknownAndIsNotRetried() {
        AgentExternalActionMapper mapper = mock(AgentExternalActionMapper.class);
        when(mapper.selectByActionKey(anyString())).thenReturn(null);
        when(mapper.insert(any(AgentExternalAction.class))).thenReturn(1);
        when(mapper.markSent(anyString())).thenReturn(1);
        ExternalActionLedgerService service = new ExternalActionLedgerService(mapper);

        Map<String, Object> result = service.executeOnce(
                "c1", "cp1", "call1", "generate_image_batch", "media-image-batch",
                Map.of("prompt", "测试"),
                () -> {
                    throw new IllegalStateException("timeout");
                });

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("status", "unknown");
        verify(mapper).finish(eq("cp1:call1:generate_image_batch"),
                eq("UNKNOWN"), eq(null), eq(null), anyString());
    }
}
