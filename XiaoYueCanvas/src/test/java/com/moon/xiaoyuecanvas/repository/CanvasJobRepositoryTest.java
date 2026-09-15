package com.moon.xiaoyuecanvas.repository;

import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.entity.CanvasJobEvent;
import com.moon.xiaoyuecanvas.mapper.CanvasJobEventMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasJobMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasJobRepositoryTest {

    @Test
    void completeCasAppendsTerminalEventWithHighWaterSequence() {
        CanvasJobMapper jobMapper = mock(CanvasJobMapper.class);
        CanvasJobEventMapper eventMapper = mock(CanvasJobEventMapper.class);
        CanvasJobRepository repository = new CanvasJobRepository(jobMapper, eventMapper);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+08:00");

        when(jobMapper.complete("job-1", "owner-1", "asset-1", "[\"asset-1\"]", now)).thenReturn(1);
        when(jobMapper.incrementEventSeq(eq("job-1"), any())).thenReturn(1);
        when(jobMapper.selectLastEventSeq("job-1")).thenReturn(4L);

        CanvasJobEvent event = repository.complete(
                "job-1", "owner-1", "asset-1", "[\"asset-1\"]", now);

        assertNotNull(event);
        assertEquals(4L, event.getSeq());
        assertEquals("job.completed", event.getType());
        ArgumentCaptor<CanvasJobEvent> captor = ArgumentCaptor.forClass(CanvasJobEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals("job.completed", captor.getValue().getType());
        assertEquals(4L, captor.getValue().getSeq());
    }

    @Test
    void cancellationCasPreventsLateCompletedAuditFromChangingState() {
        CanvasJobMapper jobMapper = mock(CanvasJobMapper.class);
        CanvasJobEventMapper eventMapper = mock(CanvasJobEventMapper.class);
        CanvasJobRepository repository = new CanvasJobRepository(jobMapper, eventMapper);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+08:00");

        when(jobMapper.complete("job-1", "owner-1", null, null, now)).thenReturn(0);

        CanvasJobEvent terminal = repository.complete("job-1", "owner-1", null, null, now);

        assertNull(terminal);
        verify(jobMapper, never()).incrementEventSeq(any(), any());
        verify(eventMapper, never()).insert(any(CanvasJobEvent.class));
    }

    @Test
    void expiredImageRecoveryMarksInterruptedWithCompatibleFailedEvent() {
        CanvasJobMapper jobMapper = mock(CanvasJobMapper.class);
        CanvasJobEventMapper eventMapper = mock(CanvasJobEventMapper.class);
        CanvasJobRepository repository = new CanvasJobRepository(jobMapper, eventMapper);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+08:00");
        CanvasJob expired = new CanvasJob();
        expired.setId("job-1");
        expired.setCapabilityId("image.generate");
        expired.setStatus("running");
        expired.setLeaseExpiresAt(now.minusMinutes(1));

        when(jobMapper.selectExpiredRunning(eq(now), eq(100))).thenReturn(List.of(expired));
        when(jobMapper.interruptExpired(eq("job-1"), eq(now), any())).thenReturn(1);
        when(jobMapper.incrementEventSeq(eq("job-1"), any())).thenReturn(1);
        when(jobMapper.selectLastEventSeq("job-1")).thenReturn(2L);

        repository.recoverExpiredRunning(now);

        ArgumentCaptor<CanvasJobEvent> captor = ArgumentCaptor.forClass(CanvasJobEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals("job.failed", captor.getValue().getType());
        assertTrue(captor.getValue().getPayload().contains("lease_expired"));
    }
}
