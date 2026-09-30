-- Canvas Run V2: durable asynchronous canvas orchestration owned by an Agent turn.
CREATE TABLE IF NOT EXISTS public.agent_canvas_runs (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id         TEXT NOT NULL,
    project_id      TEXT NOT NULL,
    turn_id         TEXT NOT NULL REFERENCES public.agent_turns(id) ON DELETE CASCADE,
    tool_call_id    TEXT NOT NULL,
    canvas_id       TEXT NOT NULL REFERENCES public.morrow_canvases(id) ON DELETE CASCADE,
    message_id      TEXT,
    title           TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    progress        INTEGER NOT NULL DEFAULT 0,
    node_count      INTEGER NOT NULL DEFAULT 0,
    edge_count      INTEGER NOT NULL DEFAULT 0,
    revision        INTEGER NOT NULL DEFAULT 0,
    plan_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_agent_canvas_runs_turn_tool UNIQUE (turn_id, tool_call_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_canvas_runs_conversation
    ON public.agent_canvas_runs(conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_canvas_runs_canvas
    ON public.agent_canvas_runs(canvas_id);

CREATE TABLE IF NOT EXISTS public.agent_canvas_run_operations (
    id             TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    run_id         TEXT NOT NULL REFERENCES public.agent_canvas_runs(id) ON DELETE CASCADE,
    operation_key  TEXT NOT NULL,
    result_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_canvas_run_operations_run_key UNIQUE (run_id, operation_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_canvas_run_operations_run
    ON public.agent_canvas_run_operations(run_id, created_at);
