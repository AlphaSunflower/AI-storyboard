CREATE TABLE IF NOT EXISTS public.storyboard_runs (
    id                TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id           TEXT NOT NULL,
    project_id        TEXT,
    conversation_id   TEXT,
    canvas_id         TEXT,
    original_script   TEXT NOT NULL,
    selected_script   TEXT,
    expansion_script  TEXT,
    stage             VARCHAR(32) NOT NULL,
    version           INTEGER NOT NULL DEFAULT 1,
    plot_assessment   TEXT NOT NULL DEFAULT '{}',
    story_bible       TEXT NOT NULL DEFAULT '{}',
    roles             TEXT NOT NULL DEFAULT '[]',
    assets            TEXT NOT NULL DEFAULT '[]',
    pseudo_shots      TEXT NOT NULL DEFAULT '[]',
    final_shots       TEXT NOT NULL DEFAULT '[]',
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_storyboard_runs_owner
    ON public.storyboard_runs(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_storyboard_runs_project
    ON public.storyboard_runs(project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.storyboard_shot_records (
    id                    TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    run_id                TEXT NOT NULL REFERENCES public.storyboard_runs(id) ON DELETE CASCADE,
    phase                 VARCHAR(16) NOT NULL,
    segment_id            VARCHAR(128) NOT NULL,
    shot_id               VARCHAR(128) NOT NULL,
    shot_ordinal          INTEGER NOT NULL,
    status                VARCHAR(32) NOT NULL,
    payload               TEXT NOT NULL DEFAULT '{}',
    reference_asset_ids   TEXT NOT NULL DEFAULT '[]',
    provider_task_id      TEXT,
    error                 TEXT,
    attempt               INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(run_id, phase, shot_id)
);

CREATE INDEX IF NOT EXISTS idx_storyboard_shot_records_run
    ON public.storyboard_shot_records(run_id, phase, shot_ordinal);
