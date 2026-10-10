CREATE TABLE IF NOT EXISTS public.agent_sandbox_sessions (
    conversation_id TEXT PRIMARY KEY REFERENCES public.conversations(id) ON DELETE CASCADE,
    status            VARCHAR(32) NOT NULL DEFAULT 'active',
    last_used_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_sessions_expiry
    ON public.agent_sandbox_sessions(status, expires_at);
