package com.moon.xiaoyuecanvas.service;

import com.moon.xiaoyuecanvas.entity.CanvasJob;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasJobStateMachineTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+08:00");

    @Test
    void queuedJobIsClaimable() {
        CanvasJob job = job("queued", "image.generate");
        assertEquals(CanvasJobStateMachine.ClaimDecision.CLAIM_QUEUED,
                CanvasJobStateMachine.decideClaim(job, now));
    }

    @Test
    void runningJobWithValidLeaseIsNotClaimedTwice() {
        CanvasJob job = job("running", "image.generate");
        job.setLeaseOwner("owner-1");
        job.setLeaseExpiresAt(now.plusMinutes(20));

        assertEquals(CanvasJobStateMachine.ClaimDecision.BUSY,
                CanvasJobStateMachine.decideClaim(job, now));
        assertTrue(CanvasJobStateMachine.hasValidLease(job, now));
    }

    @Test
    void expiredRunningJobIsRecoveredBeforeClaim() {
        CanvasJob job = job("running", "image.generate");
        job.setLeaseOwner("owner-1");
        job.setLeaseExpiresAt(now.minusSeconds(1));

        assertEquals(CanvasJobStateMachine.ClaimDecision.RECOVERABLE,
                CanvasJobStateMachine.decideClaim(job, now));
        assertFalse(CanvasJobStateMachine.hasValidLease(job, now));
    }

    @Test
    void interruptedVideoWithProviderTaskIsResumable() {
        CanvasJob job = job("interrupted", "video.generate");
        job.setProviderTaskId("provider-task-1");

        assertEquals(CanvasJobStateMachine.ClaimDecision.CLAIM_RESUMABLE,
                CanvasJobStateMachine.decideClaim(job, now));
        assertTrue(CanvasJobStateMachine.isResumable(job));
    }

    @Test
    void interruptedSynchronousImageIsNotResumable() {
        CanvasJob job = job("interrupted", "image.generate");

        assertEquals(CanvasJobStateMachine.ClaimDecision.INTERRUPTED,
                CanvasJobStateMachine.decideClaim(job, now));
        assertFalse(CanvasJobStateMachine.isResumable(job));
        assertTrue(CanvasJobStateMachine.isFinalWithoutResume(job, now));
    }

    @Test
    void cancellationRequestBlocksLateTerminalWrite() {
        CanvasJob job = job("running", "video.generate");
        job.setLeaseOwner("owner-1");
        job.setCancelRequestedAt(now.minusSeconds(1));

        assertEquals(CanvasJobStateMachine.ClaimDecision.CANCELLED,
                CanvasJobStateMachine.decideClaim(job, now));
        assertFalse(CanvasJobStateMachine.canApplyTerminal(job, "owner-1"));
    }

    private CanvasJob job(String status, String capabilityId) {
        CanvasJob job = new CanvasJob();
        job.setId("job-1");
        job.setStatus(status);
        job.setCapabilityId(capabilityId);
        return job;
    }
}
