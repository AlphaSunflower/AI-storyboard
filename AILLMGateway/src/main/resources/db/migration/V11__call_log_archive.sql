-- V11__call_log_archive.sql
-- call_log 冷热分离：热表只留最近 N 天，历史迁移到冷表 call_log_archive。
-- 采用归档表而非原生分区，规避 PG「分区表主键必须含分区键」+ MP selectById 不剪枝的副作用。
BEGIN;

CREATE TABLE IF NOT EXISTS call_log_archive (
    id            VARCHAR(64) PRIMARY KEY,
    model         VARCHAR(128),
    channel_id    VARCHAR(64),
    status        VARCHAR(32),
    duration_ms   BIGINT,
    error         TEXT,
    video_url     TEXT,
    task_id       VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_call_log_archive_created_at
    ON call_log_archive(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_call_log_archive_model_created
    ON call_log_archive(model, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_call_log_archive_task_id
    ON call_log_archive(task_id);

-- 冷热归档保留期种子（幂等；与 sys_config SPECS 键一致，落库后重启生效）
INSERT INTO sys_config (id, config_key, config_value, remark, created_at, updated_at)
VALUES ('gateway.call-log-retention-days', 'gateway.call-log-retention-days', '90',
        '调用日志热表保留天数（超出归档到 call_log_archive）', now(), now())
ON CONFLICT (config_key) DO NOTHING;

COMMIT;
