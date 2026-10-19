-- V20: 网关路由 v2 —— 多渠道负载、上游模型映射、参数覆盖、跨模型回退、多 Key、可观测指标
-- 手动执行：psql -h localhost -U postgres -d newworkflow -f V20__gateway_routing_v2.sql

-- 渠道运行参数（weight 与 priority 一起决定选路；timeout/retries 覆盖全局默认）
ALTER TABLE channel
    ADD COLUMN IF NOT EXISTS weight INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS timeout_ms INT,
    ADD COLUMN IF NOT EXISTS max_retries INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS last_checked_at TIMESTAMPTZ;

-- 路由升级为「部署」：同一公共模型名可以挂到多个渠道，按优先级 + 权重分摊
ALTER TABLE model_route
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS weight INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS upstream_model VARCHAR(128),
    ADD COLUMN IF NOT EXISTS timeout_ms INT,
    ADD COLUMN IF NOT EXISTS max_retries INT,
    ADD COLUMN IF NOT EXISTS param_overrides TEXT;

-- 同模型同渠道只保留一条路由（先建普通索引，重复数据由管理端校验拦截）
CREATE INDEX IF NOT EXISTS idx_model_route_model_channel
    ON model_route (model_name, channel_id) WHERE deleted = FALSE;

-- 跨模型回退：主模型全部渠道失败后，按 sort_order 依次尝试
CREATE TABLE IF NOT EXISTS model_fallback (
    id             VARCHAR(64) PRIMARY KEY,
    model_name     VARCHAR(128) NOT NULL,
    type           VARCHAR(32)  NOT NULL DEFAULT 'text',
    fallback_model VARCHAR(128) NOT NULL,
    sort_order     INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_model_fallback
    ON model_fallback (model_name, type, fallback_model);
CREATE INDEX IF NOT EXISTS idx_model_fallback_model
    ON model_fallback (model_name, type, sort_order);

-- 多 Key 池：一个渠道可挂多个 Key，按权重轮换；单个 Key 失败冷却
CREATE TABLE IF NOT EXISTS channel_key (
    id            VARCHAR(64) PRIMARY KEY,
    channel_id    VARCHAR(64) NOT NULL REFERENCES channel(id),
    label         VARCHAR(64),
    api_key       TEXT        NOT NULL,          -- AES 加密密文
    weight        INT         NOT NULL DEFAULT 1,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    fail_count    INT         NOT NULL DEFAULT 0,
    cooldown_until TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_channel_key_channel ON channel_key (channel_id);

-- 可观测：上游模型、第几次尝试、token 用量
ALTER TABLE call_log
    ADD COLUMN IF NOT EXISTS upstream_model VARCHAR(128),
    ADD COLUMN IF NOT EXISTS attempt INT,
    ADD COLUMN IF NOT EXISTS prompt_tokens INT,
    ADD COLUMN IF NOT EXISTS completion_tokens INT,
    ADD COLUMN IF NOT EXISTS total_tokens INT;

CREATE INDEX IF NOT EXISTS idx_call_log_channel_created
    ON call_log (channel_id, created_at DESC);
