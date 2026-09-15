package com.moon.xiaoyuecanvas.repository;

import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.entity.CanvasJobEvent;
import com.moon.xiaoyuecanvas.mapper.CanvasJobEventMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasJobMapper;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * canvas_jobs durable state boundary.
 *
 * <p>Every method that changes job state also appends its event and advances
 * {@code last_event_seq} in the same Spring transaction. SSE publishing is deliberately kept outside
 * this class and must happen after the transaction returns.
 */
@Repository
@RequiredArgsConstructor
public class CanvasJobRepository {

    private static final int EXPIRED_SCAN_LIMIT = 100;
    private static final String INTERRUPTED_ERROR = "执行服务重启或租约过期，任务已中断";

    private final CanvasJobMapper canvasJobMapper;
    private final CanvasJobEventMapper canvasJobEventMapper;

    public record Claim(CanvasJob job, CanvasJobEvent event) {}

    public record Snapshot(CanvasJob job, List<CanvasJobEvent> events) {}

    @Transactional
    public void insertQueued(CanvasJob job) {
        canvasJobMapper.insert(job);
        appendInTx(job.getId(), "job.queued", payload("jobId", job.getId(), "status", "queued"));
    }

    @Transactional
    public int recoverExpiredRunning(OffsetDateTime now) {
        List<CanvasJob> expired = canvasJobMapper.selectExpiredRunning(now, EXPIRED_SCAN_LIMIT);
        int recovered = 0;
        for (CanvasJob job : expired) {
            int rows = canvasJobMapper.interruptExpired(job.getId(), now, INTERRUPTED_ERROR);
            if (rows == 0) continue;
            recovered++;
            boolean resumable = "video.generate".equals(job.getCapabilityId())
                    && job.getProviderTaskId() != null
                    && !job.getProviderTaskId().isBlank();
            appendInTx(job.getId(), resumable ? "job.interrupted" : "job.failed", payload(
                    "jobId", job.getId(),
                    "reason", "lease_expired",
                    "message", INTERRUPTED_ERROR,
                    "providerTaskId", job.getProviderTaskId(),
                    "status", "interrupted",
                    "resumable", resumable
            ));
        }
        return recovered;
    }

    @Transactional
    public Claim claim(String jobId, String owner, OffsetDateTime now, OffsetDateTime expiresAt) {
        int rows = canvasJobMapper.claim(jobId, owner, now, expiresAt);
        if (rows == 0) return null;
        CanvasJob claimed = canvasJobMapper.selectById(jobId);
        CanvasJobEvent event = appendInTx(jobId, "job.started", payload(
                "jobId", jobId,
                "attempt", claimed != null ? claimed.getAttempt() : null,
                "resumed", claimed != null && claimed.getAttempt() != null && claimed.getAttempt() > 1
        ));
        return new Claim(claimed, event);
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String jobId, long afterSeq) {
        CanvasJob job = canvasJobMapper.selectById(jobId);
        if (job == null) return null;
        long cursor = job.getLastEventSeq() == null ? 0L : job.getLastEventSeq();
        List<CanvasJobEvent> events = canvasJobEventMapper.selectRange(jobId, Math.max(0L, afterSeq), cursor);
        return new Snapshot(job, events == null ? List.of() : List.copyOf(events));
    }

    @Transactional
    public boolean renewLease(String jobId, String owner, OffsetDateTime now, OffsetDateTime expiresAt) {
        return canvasJobMapper.renewLease(jobId, owner, now, expiresAt) > 0;
    }

    @Transactional
    public CanvasJobEvent updateProgress(String jobId, String owner, int progress,
                                         OffsetDateTime now, OffsetDateTime expiresAt) {
        if (canvasJobMapper.updateProgress(jobId, owner, progress, now, expiresAt) == 0) return null;
        double normalized = Math.max(0.0, Math.min(1.0, progress / 100.0));
        return appendInTx(jobId, "job.progress", payload(
                "jobId", jobId,
                "progress", normalized,
                "message", "正在生成"
        ));
    }

    @Transactional
    public boolean attachProviderTaskId(String jobId, String taskId, String batchId) {
        return canvasJobMapper.attachProviderTaskId(jobId, taskId, batchId) > 0;
    }

    @Transactional
    public CanvasJobEvent complete(String jobId, String owner, String resultAssetId,
                                   String resultAssetIds, OffsetDateTime now) {
        if (canvasJobMapper.complete(jobId, owner, resultAssetId, resultAssetIds, now) == 0) return null;
        return appendInTx(jobId, "job.completed", payload(
                "jobId", jobId,
                "resultAssetId", resultAssetId,
                "resultAssetIds", resultAssetIds
        ));
    }

    @Transactional
    public CanvasJobEvent fail(String jobId, String owner, String error, OffsetDateTime now) {
        if (canvasJobMapper.fail(jobId, owner, error, now) == 0) return null;
        return appendInTx(jobId, "job.failed", payload(
                "jobId", jobId,
                "error", error
        ));
    }

    @Transactional
    public CanvasJobEvent cancel(String jobId, OffsetDateTime now) {
        if (canvasJobMapper.cancel(jobId, now) == 0) return null;
        return appendInTx(jobId, "job.cancelled", payload(
                "jobId", jobId,
                "cancelledAt", now.toString()
        ));
    }

    @Transactional
    public CanvasJobEvent appendEvent(String jobId, String type, Map<String, Object> payload) {
        return appendInTx(jobId, type, payload);
    }

    public boolean isCancellationRequested(String jobId) {
        CanvasJob current = canvasJobMapper.selectById(jobId);
        return current == null
                || current.getCancelRequestedAt() != null
                || "cancelled".equals(current.getStatus())
                || "interrupted".equals(current.getStatus());
    }

    @Transactional
    public CanvasJobEvent appendLateResultAudit(String jobId, String lateType, String resultAssetIds,
                                                String error, OffsetDateTime now) {
        return appendInTx(jobId, "job.result_ignored", payload(
                "jobId", jobId,
                "lateType", lateType,
                "resultAssetIds", resultAssetIds,
                "error", error,
                "ignoredAt", now.toString()
        ));
    }

    private CanvasJobEvent appendInTx(String jobId, String type, Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.now();
        int rows = canvasJobMapper.incrementEventSeq(jobId, now);
        if (rows == 0) throw new IllegalStateException("canvas job not found: " + jobId);
        Long seq = canvasJobMapper.selectLastEventSeq(jobId);
        if (seq == null) throw new IllegalStateException("canvas job event sequence unavailable: " + jobId);

        CanvasJobEvent event = new CanvasJobEvent();
        event.setJobId(jobId);
        event.setSeq(seq);
        event.setType(type);
        event.setPayload(JsonUtil.toJson(payload));
        event.setCreatedAt(now);
        canvasJobEventMapper.insert(event);
        return event;
    }

    private static Map<String, Object> payload(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            payload.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return payload;
    }
}
