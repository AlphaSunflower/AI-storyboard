-- Generic project-scoped creative memory, self-critique records and timeline manifests.

CREATE TABLE IF NOT EXISTS public.project_creative_contexts (
    project_id             TEXT PRIMARY KEY REFERENCES public.projects(id) ON DELETE CASCADE,
    user_id                TEXT NOT NULL,
    objective              TEXT NOT NULL DEFAULT '',
    creative_direction     TEXT NOT NULL DEFAULT '',
    visual_style           TEXT NOT NULL DEFAULT '',
    references_json        JSONB NOT NULL DEFAULT '[]'::jsonb,
    hard_constraints_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    soft_constraints_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    approved_assets_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    rejected_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    feedback_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_preferences_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                BIGINT NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_creative_context_user
    ON public.project_creative_contexts(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.agent_media_critiques (
    id                    TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    project_id            TEXT REFERENCES public.projects(id) ON DELETE CASCADE,
    conversation_id       TEXT REFERENCES public.conversations(id) ON DELETE CASCADE,
    turn_id               TEXT REFERENCES public.agent_turns(id) ON DELETE SET NULL,
    item_id               TEXT NOT NULL DEFAULT '',
    media_type            VARCHAR(32) NOT NULL DEFAULT 'image',
    asset_url             TEXT NOT NULL DEFAULT '',
    decision              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    hard_constraint_pass  BOOLEAN NOT NULL DEFAULT TRUE,
    score_json            JSONB NOT NULL DEFAULT '{}'::jsonb,
    violations_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    reasons_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    repair_attempt        INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_media_critiques_turn
    ON public.agent_media_critiques(turn_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_agent_media_critiques_project
    ON public.agent_media_critiques(project_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.agent_timeline_manifests (
    id                       TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    project_id               TEXT REFERENCES public.projects(id) ON DELETE CASCADE,
    conversation_id          TEXT REFERENCES public.conversations(id) ON DELETE CASCADE,
    turn_id                  TEXT REFERENCES public.agent_turns(id) ON DELETE SET NULL,
    title                    TEXT NOT NULL DEFAULT '',
    status                   VARCHAR(32) NOT NULL DEFAULT 'draft',
    total_duration_seconds   INTEGER NOT NULL DEFAULT 0,
    manifest_json            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_timeline_manifests_project
    ON public.agent_timeline_manifests(project_id, updated_at DESC);
