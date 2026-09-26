-- Durable external-action ledger plus typed AgentScope permission tool calls.

ALTER TABLE public.agent_checkpoints
    ADD COLUMN IF NOT EXISTS runtime_tool_calls_json TEXT;

CREATE TABLE IF NOT EXISTS public.agent_external_actions (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    action_key        TEXT NOT NULL UNIQUE,
    conversation_id   TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    checkpoint_id     TEXT,
    tool_call_id      TEXT,
    tool_name         TEXT NOT NULL,
    action_type       TEXT NOT NULL,
    request_hash      TEXT,
    status            VARCHAR(32) NOT NULL,
    provider_task_id  TEXT,
    response_json     TEXT,
    last_error        TEXT,
    attempt           INTEGER NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_external_actions_checkpoint
    ON public.agent_external_actions(checkpoint_id, tool_call_id);

CREATE INDEX IF NOT EXISTS idx_agent_external_actions_status_updated
    ON public.agent_external_actions(status, updated_at);
