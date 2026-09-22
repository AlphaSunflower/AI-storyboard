-- Dynamic task orchestration persistence. Additive and backward compatible:
-- legacy code ignores these tables and columns.

CREATE TABLE IF NOT EXISTS public.agent_task_plans (
    id                    TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id       TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    goal                  TEXT NOT NULL,
    status                VARCHAR(32) NOT NULL,
    version               INTEGER NOT NULL DEFAULT 1,
    plan_json             TEXT NOT NULL DEFAULT '{}',
    plan_signature        TEXT,
    max_nodes             INTEGER NOT NULL DEFAULT 20,
    max_attempts_per_node INTEGER NOT NULL DEFAULT 3,
    max_replans           INTEGER NOT NULL DEFAULT 2,
    replan_count          INTEGER NOT NULL DEFAULT 0,
    error                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_task_plans_conversation
    ON public.agent_task_plans(conversation_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_task_plans_status
    ON public.agent_task_plans(status, updated_at ASC);

CREATE TABLE IF NOT EXISTS public.agent_task_nodes (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    plan_id          TEXT NOT NULL REFERENCES public.agent_task_plans(id) ON DELETE CASCADE,
    node_id          TEXT NOT NULL,
    capability_key   TEXT NOT NULL,
    title            TEXT,
    state            VARCHAR(32) NOT NULL,
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    hitl_policy      VARCHAR(32) NOT NULL DEFAULT 'NONE',
    evaluation_mode  VARCHAR(32) NOT NULL DEFAULT 'DETERMINISTIC',
    quality_criteria TEXT,
    output_summary   TEXT,
    async_task_id    TEXT,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(plan_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_task_nodes_ready
    ON public.agent_task_nodes(plan_id, state, created_at ASC);

CREATE TABLE IF NOT EXISTS public.agent_task_edges (
    id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    plan_id      TEXT NOT NULL REFERENCES public.agent_task_plans(id) ON DELETE CASCADE,
    from_node_id TEXT NOT NULL,
    to_node_id   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(plan_id, from_node_id, to_node_id)
);

ALTER TABLE public.agent_assets
    ADD COLUMN IF NOT EXISTS plan_id TEXT,
    ADD COLUMN IF NOT EXISTS node_id TEXT;

ALTER TABLE public.agent_checkpoints
    ADD COLUMN IF NOT EXISTS plan_id TEXT,
    ADD COLUMN IF NOT EXISTS node_id TEXT;

ALTER TABLE public.agent_loop_steps
    ADD COLUMN IF NOT EXISTS plan_id TEXT,
    ADD COLUMN IF NOT EXISTS node_id TEXT,
    ADD COLUMN IF NOT EXISTS attempt_no INTEGER;

CREATE INDEX IF NOT EXISTS idx_agent_assets_plan_node
    ON public.agent_assets(plan_id, node_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_plan_node
    ON public.agent_checkpoints(plan_id, node_id);
CREATE INDEX IF NOT EXISTS idx_agent_loop_steps_plan_node
    ON public.agent_loop_steps(plan_id, node_id, attempt_no);
