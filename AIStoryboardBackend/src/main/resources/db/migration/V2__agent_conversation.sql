-- V2__agent_conversation.sql
-- AI Agent 对话模块：会话 / 消息 / 生成资产（与分镜 scenes 无关）

CREATE TABLE IF NOT EXISTS conversations (
    id                   TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id              TEXT NOT NULL,
    project_id           TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title                TEXT NOT NULL DEFAULT '新对话',
    dify_conversation_id TEXT,
    status               TEXT NOT NULL DEFAULT 'active',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_project ON conversations(user_id, project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_messages (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             TEXT NOT NULL,
    content          TEXT NOT NULL,
    dify_message_id  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_messages_conv ON agent_messages(conversation_id, created_at);

CREATE TABLE IF NOT EXISTS agent_assets (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT REFERENCES conversations(id) ON DELETE CASCADE,
    type             TEXT NOT NULL,
    url              TEXT NOT NULL,
    prompt           TEXT,
    model            TEXT,
    status           TEXT NOT NULL DEFAULT 'queued',
    task_id          TEXT,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv ON agent_assets(conversation_id, type);
