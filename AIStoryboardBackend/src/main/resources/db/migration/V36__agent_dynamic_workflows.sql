CREATE TABLE IF NOT EXISTS public.agent_workflow_runs (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id   TEXT NOT NULL,
    user_id           TEXT NOT NULL,
    project_id        TEXT,
    mode              VARCHAR(32) NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    original_prompt   TEXT NOT NULL,
    constraint_json   TEXT NOT NULL DEFAULT '{}',
    version           BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_runs_conversation
    ON public.agent_workflow_runs(conversation_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.agent_workflow_steps (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    workflow_run_id   TEXT NOT NULL REFERENCES public.agent_workflow_runs(id) ON DELETE CASCADE,
    step_index        INTEGER NOT NULL,
    step_key          VARCHAR(128) NOT NULL,
    title             TEXT NOT NULL,
    tool_name         VARCHAR(128) NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    depends_on_json   TEXT NOT NULL DEFAULT '[]',
    input_json        TEXT NOT NULL DEFAULT '{}',
    output_json       TEXT NOT NULL DEFAULT '{}',
    marker            TEXT NOT NULL DEFAULT '',
    error             TEXT,
    attempts          INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    UNIQUE(workflow_run_id, step_index),
    UNIQUE(workflow_run_id, step_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_steps_run
    ON public.agent_workflow_steps(workflow_run_id, step_index);
