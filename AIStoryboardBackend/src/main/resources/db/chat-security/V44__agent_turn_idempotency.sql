ALTER TABLE public.agent_turns
    ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_turns_conversation_request
    ON public.agent_turns(conversation_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
