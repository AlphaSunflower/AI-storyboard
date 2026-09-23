-- AgentScope in-process ReAct state. The table is managed by AgentScope's
-- PostgresAgentStateStore and is separate from the user-visible chat transcript.

CREATE SCHEMA IF NOT EXISTS public;

CREATE TABLE IF NOT EXISTS public.agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT          NOT NULL DEFAULT 0,
    state_data TEXT         NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);

CREATE INDEX IF NOT EXISTS idx_agentscope_sessions_updated
    ON public.agentscope_sessions(updated_at DESC);
