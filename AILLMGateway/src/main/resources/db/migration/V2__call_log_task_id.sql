ALTER TABLE call_log ADD COLUMN task_id VARCHAR(128);
CREATE INDEX idx_call_log_task_id ON call_log(task_id);
