-- 挑片标记：星级 / 旗标 / 色标；以及可保存的筛选视图。
ALTER TABLE asset.library_assets
    ADD COLUMN IF NOT EXISTS rating SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS flag VARCHAR(8),
    ADD COLUMN IF NOT EXISTS color_label VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_library_assets_marks
    ON asset.library_assets (owner_user_id, owner_project_id, rating DESC, flag, color_label)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS asset.library_saved_views (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    scope               VARCHAR(16) NOT NULL CHECK (scope IN ('PROJECT', 'GLOBAL')),
    owner_project_id    TEXT,
    name                TEXT NOT NULL,
    normalized_name     TEXT NOT NULL,
    filters             JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort                JSONB NOT NULL DEFAULT '{}'::jsonb,
    view_mode           VARCHAR(16) NOT NULL DEFAULT 'folders',
    pinned              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT library_saved_views_scope_binding_check CHECK (
        (scope = 'PROJECT' AND owner_project_id IS NOT NULL)
        OR (scope = 'GLOBAL' AND owner_project_id IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_library_saved_views_name
    ON asset.library_saved_views (
        owner_user_id,
        scope,
        COALESCE(owner_project_id, ''),
        normalized_name
    );

CREATE INDEX IF NOT EXISTS idx_library_saved_views_owner
    ON asset.library_saved_views (owner_user_id, scope, owner_project_id, pinned DESC, updated_at DESC);
