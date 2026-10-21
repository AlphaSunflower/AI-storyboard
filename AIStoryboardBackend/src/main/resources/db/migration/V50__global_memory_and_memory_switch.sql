-- 全局记忆（每账号一条，用户维护、跨项目生效）与记忆管理总开关。
-- 缺行视为启用，兼容开关上线前的账号。
CREATE TABLE IF NOT EXISTS agent_global_memories (
    user_id      VARCHAR(64) PRIMARY KEY,
    content      TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_memory_settings (
    user_id    VARCHAR(64) PRIMARY KEY,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
