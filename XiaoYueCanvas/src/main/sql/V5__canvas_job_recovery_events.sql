-- V5__canvas_job_recovery_events.sql
-- Morrow canvas job durability: lease-based execution, CAS state transitions, and replayable events.
-- XiaoYueCanvas does not use Flyway; apply this file with psql after V4.

BEGIN;

ALTER TABLE public.canvas_jobs
    ADD COLUMN IF NOT EXISTS attempt             integer      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancel_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS started_at          timestamptz,
    ADD COLUMN IF NOT EXISTS completed_at        timestamptz,
    ADD COLUMN IF NOT EXISTS heartbeat_at        timestamptz,
    ADD COLUMN IF NOT EXISTS lease_owner         text,
    ADD COLUMN IF NOT EXISTS lease_expires_at    timestamptz,
    ADD COLUMN IF NOT EXISTS state_version       bigint       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_event_seq      bigint       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS result_asset_ids    text,
    ADD COLUMN IF NOT EXISTS provider_task_id    text,
    ADD COLUMN IF NOT EXISTS batch_id            text;

-- Pre-existing running rows have no lease owner. Mark them interrupted so the
-- recovery path can either resume provider-backed videos or require a retry.
UPDATE public.canvas_jobs
SET status = 'interrupted',
    error = COALESCE(error, '服务升级后执行租约缺失，已等待恢复或重试'),
    completed_at = COALESCE(completed_at, now()),
    state_version = state_version + 1,
    updated_at = now()
WHERE status = 'running'
  AND lease_expires_at IS NULL;

-- Claim/recovery path: find queued work and expired running leases.
CREATE INDEX IF NOT EXISTS idx_canvas_jobs_status_lease
    ON public.canvas_jobs(status, lease_expires_at)
    WHERE status = 'running';

CREATE INDEX IF NOT EXISTS idx_canvas_jobs_status_created
    ON public.canvas_jobs(status, created_at);

-- Batch recovery and batch-level status queries.
CREATE INDEX IF NOT EXISTS idx_canvas_jobs_batch_status_created
    ON public.canvas_jobs(batch_id, status, created_at)
    WHERE batch_id IS NOT NULL;

-- Resume polling after a service restart.
CREATE INDEX IF NOT EXISTS idx_canvas_jobs_provider_task
    ON public.canvas_jobs(provider_task_id)
    WHERE provider_task_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.canvas_job_events (
    job_id     text        NOT NULL REFERENCES public.canvas_jobs(id) ON DELETE CASCADE,
    seq        bigint      NOT NULL,
    type       varchar(64) NOT NULL,
    payload    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, seq)
);

-- Explicit lookup index for ordered replay; the primary key already guarantees uniqueness.
CREATE INDEX IF NOT EXISTS idx_canvas_job_events_job_seq
    ON public.canvas_job_events(job_id, seq);

COMMIT;
