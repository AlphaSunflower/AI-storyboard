-- 项目级/会话级长期记忆。只存显式写入的事实，不自动灌对话。
CREATE TABLE IF NOT EXISTS agent_memories (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    project_id      VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    kind            VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    source          VARCHAR(32) NOT NULL DEFAULT 'agent',
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memories_user_project_hash
    ON agent_memories (user_id, project_id, content_hash);
CREATE INDEX IF NOT EXISTS idx_agent_memories_user_updated
    ON agent_memories (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_memories_project_updated
    ON agent_memories (project_id, updated_at DESC);
