-- Unified asynchronous AIGC tool tasks: batch image generation and long-running tools.

CREATE TABLE IF NOT EXISTS public.agent_async_tasks (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id   TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id           TEXT NOT NULL,
    session_id        TEXT,
    tool_call_id      TEXT,
    tool_name         TEXT NOT NULL,
    kind              VARCHAR(32) NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'queued',
    request_json      TEXT,
    result_json       TEXT,
    asset_ids_json    TEXT,
    result_urls_json  TEXT,
    message_id        TEXT REFERENCES public.agent_messages(id) ON DELETE SET NULL,
    error             TEXT,
    idempotency_key   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_async_tasks_idempotency
    ON public.agent_async_tasks(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_conversation_created
    ON public.agent_async_tasks(conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_status_created
    ON public.agent_async_tasks(status, created_at ASC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_messages_async_task
    ON public.agent_messages ((metadata->>'asyncTaskId'))
    WHERE metadata ? 'asyncTaskId';
