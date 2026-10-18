-- 子智能体任务图的节点级审计
CREATE TABLE IF NOT EXISTS public.agent_subagent_runs (
    id              VARCHAR(180) PRIMARY KEY,
    graph_id        VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64),
    turn_id         VARCHAR(64),
    tool_call_id    VARCHAR(64),
    node_id         VARCHAR(64)  NOT NULL,
    role            VARCHAR(64),
    status          VARCHAR(32)  NOT NULL,
    instruction     TEXT,
    output          TEXT,
    error           TEXT,
    tokens          INTEGER DEFAULT 0,
    duration_ms     BIGINT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_subagent_runs_graph
    ON public.agent_subagent_runs (graph_id);
CREATE INDEX IF NOT EXISTS idx_agent_subagent_runs_conversation
    ON public.agent_subagent_runs (conversation_id, created_at DESC);
