package com.moon.xiaoyuecanvas.service;

import com.moon.xiaoyuecanvas.entity.CanvasJob;

import java.time.OffsetDateTime;

/**
 * Pure state decisions for durable canvas execution. Persistence code performs CAS updates; this
 * class is the single place that defines what those updates mean.
 */
public final class CanvasJobStateMachine {

    public enum ClaimDecision {
        CLAIM_QUEUED,
        CLAIM_RESUMABLE,
        BUSY,
        RECOVERABLE,
        INTERRUPTED,
        TERMINAL,
        CANCELLED
    }

    private CanvasJobStateMachine() {}

    public static ClaimDecision decideClaim(CanvasJob job, OffsetDateTime now) {
        if (job == null) return ClaimDecision.TERMINAL;
        if (isTerminal(job.getStatus())) return ClaimDecision.TERMINAL;
        if (job.getCancelRequestedAt() != null || "cancelled".equals(job.getStatus())) {
            return ClaimDecision.CANCELLED;
        }
        if ("queued".equals(job.getStatus())) return ClaimDecision.CLAIM_QUEUED;
        if ("running".equals(job.getStatus())) {
            return hasValidLease(job, now) ? ClaimDecision.BUSY : ClaimDecision.RECOVERABLE;
        }
        if ("interrupted".equals(job.getStatus())) {
            return isResumable(job) ? ClaimDecision.CLAIM_RESUMABLE : ClaimDecision.INTERRUPTED;
        }
        return ClaimDecision.TERMINAL;
    }

    public static boolean hasValidLease(CanvasJob job, OffsetDateTime now) {
        return job != null
                && "running".equals(job.getStatus())
                && job.getLeaseOwner() != null
                && !job.getLeaseOwner().isBlank()
                && job.getLeaseExpiresAt() != null
                && job.getLeaseExpiresAt().isAfter(now);
    }

    public static boolean isResumable(CanvasJob job) {
        return job != null
                && "video.generate".equals(job.getCapabilityId())
                && job.getProviderTaskId() != null
                && !job.getProviderTaskId().isBlank();
    }

    public static boolean canApplyTerminal(CanvasJob job, String owner) {
        return job != null
                && "running".equals(job.getStatus())
                && job.getCancelRequestedAt() == null
                && owner != null
                && owner.equals(job.getLeaseOwner());
    }

    public static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    public static boolean isFinalWithoutResume(CanvasJob job, OffsetDateTime now) {
        ClaimDecision decision = decideClaim(job, now);
        return decision == ClaimDecision.INTERRUPTED
                || decision == ClaimDecision.TERMINAL
                || decision == ClaimDecision.CANCELLED;
    }
}
