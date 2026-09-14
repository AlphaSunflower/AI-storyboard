-- 独立 asset-service 的逻辑关联与生成结果 outbox。
-- 测试库可直接重建；旧 assets/asset_images/scene_assets 在调用方全部切完后由 reset 脚本删除。

CREATE TABLE IF NOT EXISTS scene_asset_links (
    scene_id       TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    asset_id       TEXT NOT NULL,
    purpose        VARCHAR(16) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(scene_id, asset_id, purpose)
);
CREATE INDEX IF NOT EXISTS idx_scene_asset_links_asset ON scene_asset_links(asset_id);

CREATE TABLE IF NOT EXISTS asset_outbox (
    event_id             TEXT PRIMARY KEY,
    producer             VARCHAR(32) NOT NULL,
    generation_record_id TEXT NOT NULL,
    result_index         INTEGER NOT NULL,
    group_id             TEXT,
    result_asset_id      TEXT,
    payload_json         TEXT NOT NULL,
    status               VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    next_retry_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner          TEXT,
    lease_until          TIMESTAMPTZ,
    error_code           VARCHAR(64),
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(producer, generation_record_id, result_index)
);
CREATE INDEX IF NOT EXISTS idx_asset_outbox_claim
    ON asset_outbox(status, next_retry_at, created_at);

CREATE TABLE IF NOT EXISTS scene_material_group_snapshots (
    scene_id       TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    snapshot_id    TEXT NOT NULL,
    group_id       TEXT NOT NULL,
    group_version  BIGINT NOT NULL,
    content_hash   CHAR(64) NOT NULL,
    snapshot_json  TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(scene_id, snapshot_id)
);
