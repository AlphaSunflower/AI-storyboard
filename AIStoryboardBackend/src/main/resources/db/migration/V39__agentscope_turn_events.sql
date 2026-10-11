-- AgentScope turn/event projection. Old orchestration tables remain intact and read-only.

CREATE TABLE IF NOT EXISTS public.agent_turns (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    status           VARCHAR(32) NOT NULL DEFAULT 'running',
    runtime_engine   VARCHAR(64) NOT NULL DEFAULT 'agentscope-java-2.0.4',
    input_text       TEXT,
    last_sequence    INTEGER NOT NULL DEFAULT 0,
    checkpoint_id    TEXT,
    error            TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_turns_conversation_started
    ON public.agent_turns(conversation_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_turns_status
    ON public.agent_turns(status, started_at ASC);

CREATE TABLE IF NOT EXISTS public.agent_turn_events (
    id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    turn_id      TEXT NOT NULL REFERENCES public.agent_turns(id) ON DELETE CASCADE,
    sequence     INTEGER NOT NULL,
    event_type   VARCHAR(128) NOT NULL,
    payload      TEXT NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(turn_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_agent_turn_events_replay
    ON public.agent_turn_events(turn_id, sequence ASC);

-- Preserve history while preventing pre-AgentScope work from resuming on the retired runtime.
UPDATE public.agent_checkpoints
SET status = 'expired'
WHERE status = 'pending'
  AND owner_engine IS DISTINCT FROM 'AGENTSCOPE';

UPDATE public.agent_task_plans
SET status = 'CANCELLED',
    error = 'agentscope-migration'
WHERE status IN ('DRAFT', 'READY', 'RUNNING', 'WAITING_HUMAN', 'WAITING_ASYNC', 'REPLANNING');

UPDATE public.conversations
SET engine_version = 'agentscope-v2'
WHERE status = 'active';

