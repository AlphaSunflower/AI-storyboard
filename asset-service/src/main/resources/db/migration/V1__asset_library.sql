CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE asset.library_blobs (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    object_key          TEXT NOT NULL UNIQUE,
    sha256              CHAR(64) NOT NULL UNIQUE,
    mime_type           VARCHAR(128) NOT NULL,
    size_bytes          BIGINT NOT NULL CHECK (size_bytes >= 0),
    width               INTEGER,
    height              INTEGER,
    duration_seconds    DOUBLE PRECISION,
    media_type          VARCHAR(16) NOT NULL CHECK (media_type IN ('IMAGE','VIDEO','AUDIO')),
    status              VARCHAR(24) NOT NULL DEFAULT 'READY',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset.library_assets (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    scope               VARCHAR(16) NOT NULL CONSTRAINT library_assets_scope_value_check CHECK (scope IN ('PROJECT','GLOBAL')),
    owner_project_id    TEXT,
    asset_type          VARCHAR(16) NOT NULL CHECK (asset_type IN ('IMAGE','VIDEO','AUDIO','AI_ASSET','GROUP')),
    ai_kind             VARCHAR(16),
    name                TEXT NOT NULL,
    description         TEXT,
    ai_description      TEXT,
    manual_override     BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    source_type         VARCHAR(24),
    source_id           TEXT,
    cover_url           TEXT,
    search_text         TEXT NOT NULL DEFAULT '',
    version             BIGINT NOT NULL DEFAULT 1,
    deleted_at          TIMESTAMPTZ,
    purge_after         TIMESTAMPTZ,
    purge_blocked_at    TIMESTAMPTZ,
    purged_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT library_assets_scope_check CHECK (
        (scope = 'PROJECT' AND owner_project_id IS NOT NULL)
        OR (scope = 'GLOBAL' AND owner_project_id IS NULL)
    )
);
CREATE INDEX idx_library_assets_owner_status ON asset.library_assets(owner_user_id, status, updated_at DESC);
CREATE INDEX idx_library_assets_project ON asset.library_assets(owner_project_id, status, updated_at DESC);
CREATE INDEX idx_library_assets_search ON asset.library_assets USING GIN (search_text gin_trgm_ops);

CREATE TABLE asset.library_media (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    blob_id             TEXT NOT NULL REFERENCES asset.library_blobs(id),
    sort_order          INTEGER NOT NULL DEFAULT 0,
    role                VARCHAR(24) NOT NULL DEFAULT 'REFERENCE',
    is_primary          BOOLEAN NOT NULL DEFAULT FALSE,
    constraint_text     TEXT,
    status              VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(asset_id, blob_id, sort_order)
);
CREATE INDEX idx_library_media_asset ON asset.library_media(asset_id, sort_order);
CREATE INDEX idx_library_media_blob ON asset.library_media(blob_id);

CREATE TABLE asset.library_project_links (
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    project_id          TEXT NOT NULL,
    added_by_user_id    TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(asset_id, project_id)
);
CREATE INDEX idx_library_project_links_project ON asset.library_project_links(project_id, created_at DESC);

CREATE TABLE asset.library_tags (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    name                TEXT NOT NULL,
    normalized_name     TEXT NOT NULL,
    color               VARCHAR(16),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_user_id, normalized_name)
);

CREATE TABLE asset.library_asset_tags (
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    tag_id              TEXT NOT NULL REFERENCES asset.library_tags(id) ON DELETE CASCADE,
    source              VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(asset_id, tag_id)
);

CREATE TABLE asset.library_ai_tag_suppressions (
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    normalized_name     TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(asset_id, normalized_name)
);

CREATE TABLE asset.library_groups (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    asset_id            TEXT NOT NULL UNIQUE REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    owner_user_id       TEXT NOT NULL,
    scope               VARCHAR(16) NOT NULL CHECK (scope IN ('PROJECT','GLOBAL')),
    owner_project_id    TEXT,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset.library_group_items (
    group_id            TEXT NOT NULL REFERENCES asset.library_groups(id) ON DELETE CASCADE,
    media_id            TEXT NOT NULL REFERENCES asset.library_media(id),
    member_asset_id     TEXT NOT NULL REFERENCES asset.library_assets(id),
    sort_order          INTEGER NOT NULL,
    role                VARCHAR(24) NOT NULL DEFAULT 'REFERENCE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_id, media_id),
    UNIQUE(group_id, sort_order)
);

CREATE TABLE asset.library_group_snapshots (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    group_id            TEXT NOT NULL REFERENCES asset.library_groups(id) ON DELETE RESTRICT,
    group_version       BIGINT NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    target              VARCHAR(16) NOT NULL DEFAULT 'VIDEO',
    created_by_user_id  TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(group_id, group_version, content_hash, target)
);

CREATE TABLE asset.library_group_snapshot_items (
    snapshot_id         TEXT NOT NULL REFERENCES asset.library_group_snapshots(id) ON DELETE CASCADE,
    asset_id            TEXT NOT NULL,
    media_id            TEXT NOT NULL,
    blob_id             TEXT NOT NULL,
    object_key          TEXT NOT NULL,
    media_type          VARCHAR(16) NOT NULL,
    role                VARCHAR(24) NOT NULL,
    sort_order          INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, media_id),
    UNIQUE(snapshot_id, sort_order)
);

CREATE TABLE asset.library_group_outputs (
    group_id            TEXT NOT NULL REFERENCES asset.library_groups(id) ON DELETE CASCADE,
    producer            TEXT NOT NULL,
    generation_record_id TEXT NOT NULL,
    result_index        INTEGER NOT NULL,
    result_asset_id      TEXT NOT NULL REFERENCES asset.library_assets(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(producer, generation_record_id, result_index)
);

CREATE TABLE asset.library_import_jobs (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    project_id          TEXT,
    scope               VARCHAR(16) NOT NULL,
    manifest_hash       CHAR(64) NOT NULL,
    confirmation_hash   CHAR(64) NOT NULL,
    confirmation_expires_at TIMESTAMPTZ NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    total_count         INTEGER NOT NULL DEFAULT 0,
    total_bytes         BIGINT NOT NULL DEFAULT 0,
    ignored_count       INTEGER NOT NULL DEFAULT 0,
    processed_count     INTEGER NOT NULL DEFAULT 0,
    succeeded_count     INTEGER NOT NULL DEFAULT 0,
    duplicate_count     INTEGER NOT NULL DEFAULT 0,
    failed_count        INTEGER NOT NULL DEFAULT 0,
    error_code          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset.library_import_items (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    job_id              TEXT NOT NULL REFERENCES asset.library_import_jobs(id) ON DELETE CASCADE,
    item_key            TEXT NOT NULL,
    relative_path       TEXT NOT NULL,
    file_name           TEXT NOT NULL,
    size_bytes          BIGINT NOT NULL,
    mime_type           VARCHAR(128),
    sha256              CHAR(64),
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    asset_id            TEXT REFERENCES asset.library_assets(id),
    error_code          VARCHAR(64),
    error_message       TEXT,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(job_id, item_key)
);

CREATE TABLE asset.library_analysis_tasks (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner         TEXT,
    lease_until         TIMESTAMPTZ,
    error_code          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(asset_id)
);

CREATE TABLE asset.library_storage_cleanup_tasks (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    object_key          TEXT NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner         TEXT,
    lease_until         TIMESTAMPTZ,
    error_code          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset.library_upload_sessions (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    project_id          TEXT,
    file_name           TEXT NOT NULL,
    relative_path       TEXT,
    mime_type           VARCHAR(128) NOT NULL,
    size_bytes          BIGINT NOT NULL,
    sha256              CHAR(64) NOT NULL,
    object_key          TEXT NOT NULL,
    minio_upload_id     TEXT NOT NULL,
    part_size_bytes     BIGINT NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset.library_idempotency_keys (
    owner_user_id       TEXT NOT NULL,
    operation           VARCHAR(64) NOT NULL,
    idempotency_key     TEXT NOT NULL,
    resource_id         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(owner_user_id, operation, idempotency_key)
);
