-- 失败节点重跑所需的原始任务/任务图快照；旧行允许为空。
ALTER TABLE agent_subagent_runs
    ADD COLUMN IF NOT EXISTS task_json TEXT,
    ADD COLUMN IF NOT EXISTS graph_json TEXT,
    ADD COLUMN IF NOT EXISTS context_ref VARCHAR(512);
