ALTER TABLE public.agent_async_tasks
    ADD COLUMN IF NOT EXISTS wakeup_status VARCHAR(32) NOT NULL DEFAULT 'not_required',
    ADD COLUMN IF NOT EXISTS wakeup_enqueued_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS wakeup_consumed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS wakeup_message_id TEXT REFERENCES public.agent_messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS wakeup_error TEXT,
    ADD COLUMN IF NOT EXISTS wakeup_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_wakeup
    ON public.agent_async_tasks(wakeup_status, updated_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_messages_wakeup_task
    ON public.agent_messages ((metadata->>'wakeupTaskId'))
    WHERE metadata ? 'wakeupTaskId';
