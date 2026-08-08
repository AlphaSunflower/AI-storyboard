-- 渠道表：上游供应商（Laozhang / Gemini / MiniMax）
CREATE TABLE channel (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'openai_compatible',  -- openai_compatible | gemini | minimax
    base_url    VARCHAR(512) NOT NULL,
    api_key     TEXT         NOT NULL,        -- AES 加密密文
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INT          NOT NULL DEFAULT 0,   -- 同模型多渠道时升序取第一个
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 模型路由表：模型名 → 渠道映射（非唯一：一个模型可指向多个渠道按 priority 轮换）
CREATE TABLE model_route (
    id            VARCHAR(64) PRIMARY KEY,
    model_name    VARCHAR(128) NOT NULL,
    channel_id    VARCHAR(64)  NOT NULL REFERENCES channel(id),
    default_params TEXT,                          -- JSON：size/temperature 等默认参数
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 业务调用 Key 表（/v1/** 静态鉴权）
CREATE TABLE gateway_api_key (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    key_hash    VARCHAR(128) NOT NULL UNIQUE,    -- SHA-256 哈希（明文仅签发时显示一次）
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 管理后台用户表
CREATE TABLE admin_user (
    id            VARCHAR(64) PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash TEXT         NOT NULL,         -- scrypt 哈希
    role          VARCHAR(32)  NOT NULL DEFAULT 'admin',
    status        VARCHAR(32)  NOT NULL DEFAULT 'enabled',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 调用日志表（异步落库；video_url 暂存 MiniMax 限时直链供下载端点使用）
-- 注：日志不做逻辑删除，故无 deleted 列（与 CallLog 实体一致，无 updatedAt/deleted）
CREATE TABLE call_log (
    id            VARCHAR(64) PRIMARY KEY,
    model         VARCHAR(128),
    channel_id    VARCHAR(64),
    status        VARCHAR(32),
    duration_ms   BIGINT,
    error         TEXT,
    video_url     TEXT,                          -- 视频 succeeded 时暂存 content.url（限时）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_call_log_created_at ON call_log(created_at DESC);
CREATE INDEX idx_call_log_model ON call_log(model);
