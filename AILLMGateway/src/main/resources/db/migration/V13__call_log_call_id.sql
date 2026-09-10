-- V13__call_log_call_id.sql
-- 逻辑调用 ID：同一次业务调用内的重试复用同一值，用于串联热表/归档表日志。
ALTER TABLE call_log ADD COLUMN IF NOT EXISTS call_id VARCHAR(128);
ALTER TABLE call_log_archive ADD COLUMN IF NOT EXISTS call_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_call_log_call_id ON call_log(call_id);
CREATE INDEX IF NOT EXISTS idx_call_log_archive_call_id ON call_log_archive(call_id);
