-- 动态角色/工具/技能包。P0 只加载角色 JSON，工具代码包后续沿用。
CREATE TABLE IF NOT EXISTS agent_packages (
    id            VARCHAR(64) PRIMARY KEY,
    kind          VARCHAR(16) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    version       VARCHAR(32) NOT NULL,
    manifest_json TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_packages_kind_name_version
    ON agent_packages (kind, name, version);
CREATE INDEX IF NOT EXISTS idx_agent_packages_kind_enabled
    ON agent_packages (kind, enabled);
