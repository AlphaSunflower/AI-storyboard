-- Independent canvas session lifecycle. Canvas sessions no longer reuse HITL checkpoints.

CREATE TABLE IF NOT EXISTS public.agent_canvas_sessions (
    id                    TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id       TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id               TEXT NOT NULL,
    project_id            TEXT NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    turn_id               TEXT NOT NULL REFERENCES public.agent_turns(id) ON DELETE CASCADE,
    runtime_reply_id      TEXT NOT NULL,
    runtime_tool_call_id  TEXT NOT NULL,
    canvas_id             TEXT REFERENCES public.morrow_canvases(id) ON DELETE SET NULL,
    canvas_title          TEXT NOT NULL DEFAULT '',
    intent                TEXT NOT NULL DEFAULT '',
    status                VARCHAR(32) NOT NULL DEFAULT 'pending_selection',
    choices_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    media_refs_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    error                 TEXT,
    expires_at            TIMESTAMPTZ NOT NULL,
    ready_at              TIMESTAMPTZ,
    resumed_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_canvas_sessions_conversation
    ON public.agent_canvas_sessions(conversation_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_canvas_sessions_turn
    ON public.agent_canvas_sessions(turn_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_canvas_sessions_active_turn
    ON public.agent_canvas_sessions(conversation_id, turn_id)
    WHERE status IN ('pending_selection', 'pending_open', 'opening', 'ready');
