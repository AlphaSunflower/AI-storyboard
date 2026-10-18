-- 子 Agent 节点恢复：同一 graphId 的 COMPLETED 节点可直接复用输出。
-- 审计查询按 (graph_id, status) 过滤，避免重试时全表扫描。
CREATE INDEX IF NOT EXISTS idx_agent_subagent_runs_graph_status
    ON agent_subagent_runs (graph_id, status);
