-- Unified Agent Loop: session engine ownership, checkpoint recovery ownership,
-- media generation correlation, and persisted loop traces.

ALTER TABLE public.conversations
    ADD COLUMN IF NOT EXISTS orchestration_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN IF NOT EXISTS engine_version VARCHAR(64) NOT NULL DEFAULT 'plan-graph-v1',
    ADD COLUMN IF NOT EXISTS debug_comparison_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE public.agent_checkpoints
    ADD COLUMN IF NOT EXISTS owner_engine VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN IF NOT EXISTS state_schema_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE public.agent_assets
    ADD COLUMN IF NOT EXISTS loop_run_id TEXT,
    ADD COLUMN IF NOT EXISTS chain_run_id TEXT,
    ADD COLUMN IF NOT EXISTS idempotency_key TEXT,
    ADD COLUMN IF NOT EXISTS request_fingerprint TEXT,
    ADD COLUMN IF NOT EXISTS engine_version VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_assets_idempotency_key
    ON public.agent_assets(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_assets_loop_run
    ON public.agent_assets(loop_run_id);

CREATE TABLE IF NOT EXISTS public.agent_loop_runs (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id   TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    turn_id           TEXT,
    trace_id          TEXT NOT NULL,
    engine_version    VARCHAR(64) NOT NULL,
    current_chain     VARCHAR(32),
    status            VARCHAR(32) NOT NULL DEFAULT 'running',
    decision          VARCHAR(32),
    state             TEXT NOT NULL DEFAULT '{}',
    checkpoint_id     TEXT,
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_loop_runs_conversation_created
    ON public.agent_loop_runs(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_loop_runs_trace
    ON public.agent_loop_runs(trace_id);

CREATE TABLE IF NOT EXISTS public.agent_loop_steps (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    loop_run_id       TEXT NOT NULL REFERENCES public.agent_loop_runs(id) ON DELETE CASCADE,
    step_index        INTEGER NOT NULL,
    chain             VARCHAR(32),
    phase             VARCHAR(64),
    status            VARCHAR(32) NOT NULL,
    decision          VARCHAR(32),
    input_summary     TEXT,
    output_summary    TEXT,
    tool_call_id      TEXT,
    job_id            TEXT,
    error             TEXT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_loop_steps_run_index
    ON public.agent_loop_steps(loop_run_id, step_index);

CREATE TABLE IF NOT EXISTS public.agent_handoffs (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    loop_run_id       TEXT NOT NULL REFERENCES public.agent_loop_runs(id) ON DELETE CASCADE,
    from_chain        VARCHAR(32) NOT NULL,
    to_chain          VARCHAR(32) NOT NULL,
    reason            TEXT,
    state             TEXT NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_handoffs_run
    ON public.agent_handoffs(loop_run_id, created_at ASC);
