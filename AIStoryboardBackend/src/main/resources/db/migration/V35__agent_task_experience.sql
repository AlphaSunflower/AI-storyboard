ALTER TABLE public.agent_async_tasks
    ADD COLUMN IF NOT EXISTS title TEXT,
    ADD COLUMN IF NOT EXISTS stage VARCHAR(64),
    ADD COLUMN IF NOT EXISTS progress INTEGER,
    ADD COLUMN IF NOT EXISTS result_summary TEXT,
    ADD COLUMN IF NOT EXISTS anchor_message_id TEXT REFERENCES public.agent_messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS parent_task_id TEXT REFERENCES public.agent_async_tasks(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS context_consumed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_parent
    ON public.agent_async_tasks(parent_task_id);

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_anchor
    ON public.agent_async_tasks(anchor_message_id);

UPDATE public.agent_async_tasks
SET context_consumed_at = now()
WHERE context_consumed_at IS NULL;
