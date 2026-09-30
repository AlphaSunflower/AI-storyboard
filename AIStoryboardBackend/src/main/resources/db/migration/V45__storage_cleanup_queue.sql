CREATE TABLE IF NOT EXISTS public.storage_cleanup_queue (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    object_url      TEXT NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'pending',
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_storage_cleanup_pending
    ON public.storage_cleanup_queue(status, next_attempt_at, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_cleanup_object_pending
    ON public.storage_cleanup_queue(object_url)
    WHERE status IN ('pending', 'running');
