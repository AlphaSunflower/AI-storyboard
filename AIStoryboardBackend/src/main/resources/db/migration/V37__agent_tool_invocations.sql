-- Durable per-tool execution ledger for Agent tool middleware and audit views.

CREATE TABLE IF NOT EXISTS public.agent_tool_invocations (
    id                       TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    invocation_key           TEXT NOT NULL UNIQUE,
    trace_id                 TEXT,
    loop_run_id              TEXT,
    loop_step_id             TEXT,
    task_id                  TEXT,
    conversation_id          TEXT REFERENCES public.conversations(id) ON DELETE SET NULL,
    user_id                  TEXT,
    project_id               TEXT,
    tool_call_id             TEXT,
    tool_name                TEXT NOT NULL,
    tool_kind                VARCHAR(32),
    source                   VARCHAR(32) NOT NULL,
    status                   VARCHAR(32) NOT NULL,
    attempt                  INTEGER NOT NULL DEFAULT 1,
    input_hash               TEXT,
    input_summary            TEXT,
    output_hash              TEXT,
    output_summary           TEXT,
    error_code               TEXT,
    error_message            TEXT,
    external_action_key      TEXT,
    provider_task_id         TEXT,
    result_asset_ids         TEXT,
    expected_reference_count INTEGER,
    resolved_reference_count INTEGER,
    applied_reference_count  INTEGER,
    skill_version            TEXT,
    model                    TEXT,
    engine_version           TEXT,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ,
    duration_ms              BIGINT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_user_created
    ON public.agent_tool_invocations(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_task_created
    ON public.agent_tool_invocations(task_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_conversation_created
    ON public.agent_tool_invocations(conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_tool_created
    ON public.agent_tool_invocations(tool_name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_status_created
    ON public.agent_tool_invocations(status, created_at ASC);
