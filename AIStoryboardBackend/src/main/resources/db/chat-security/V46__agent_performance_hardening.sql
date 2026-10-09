-- Additive performance hardening for Agent hot paths.

ALTER TABLE public.conversations
    ADD COLUMN IF NOT EXISTS agentscope_session_initialized_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_async_tasks_user_updated
    ON public.agent_async_tasks(user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_messages_conversation_created_id
    ON public.agent_messages(conversation_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_tool_invocations_user_started_id
    ON public.agent_tool_invocations(user_id, started_at DESC, id DESC);
