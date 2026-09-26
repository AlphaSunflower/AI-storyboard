-- Unified recovery leases and AgentScope checkpoint v3 runtime references.

CREATE TABLE IF NOT EXISTS public.agent_recovery_leases (
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(255) NOT NULL,
    lease_owner     VARCHAR(128) NOT NULL,
    lease_until     TIMESTAMPTZ NOT NULL,
    attempt         INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_type, resource_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_recovery_leases_next_attempt
    ON public.agent_recovery_leases(next_attempt_at, lease_until);

ALTER TABLE public.agent_checkpoints
    ADD COLUMN IF NOT EXISTS runtime_session_id TEXT,
    ADD COLUMN IF NOT EXISTS runtime_reply_id TEXT,
    ADD COLUMN IF NOT EXISTS runtime_tool_call_id TEXT,
    ADD COLUMN IF NOT EXISTS runtime_tool_name TEXT,
    ADD COLUMN IF NOT EXISTS resume_payload TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_status_expiration
    ON public.agent_checkpoints(status, expiration_time);

-- v3 persists typed runtime references. Older AgentScope pending cards are not
-- recoverable without guessing their internal reply/tool state, so fail them
-- explicitly instead of attempting a potentially wrong continuation.
UPDATE public.agent_checkpoints
SET status = 'expired',
    updated_at = now()
WHERE owner_engine = 'AGENTSCOPE'
  AND status = 'pending'
  AND COALESCE(state_schema_version, 0) < 3;
