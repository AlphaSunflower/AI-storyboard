-- 会话级资产组绑定：保存最初采用的快照；组变化后由用户手动刷新。
CREATE TABLE IF NOT EXISTS agent_conversation_asset_groups (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    group_id         TEXT NOT NULL,
    snapshot_id      TEXT NOT NULL,
    group_version    BIGINT NOT NULL DEFAULT 0,
    content_hash     CHAR(64) NOT NULL DEFAULT '',
    target           VARCHAR(16) NOT NULL DEFAULT 'AGENT',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conversation_id, group_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_conversation_asset_groups_conversation
    ON agent_conversation_asset_groups(conversation_id, created_at);
