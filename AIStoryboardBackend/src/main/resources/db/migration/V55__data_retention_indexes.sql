-- 数据保留期清理（2026-10-21）
--
-- 目标：让 agent_turn_events（每次 delta 一行）等大表能按保留天数分批删。
-- 没有这些索引时，DELETE ... WHERE created_at < cutoff 会变成全表扫描 + 大事务，
-- 在百万行量级上会拖垮数据库。
--
-- 项目未接 Flyway，需手动执行：
--   psql -h <host> -p 5432 -U postgres -d <db> -f V55__data_retention_indexes.sql
--
-- 说明：
--   * agent_turn_events 现有索引是 (turn_id, sequence)，服务回放；按时间清理需要单独的 created_at 索引。
--   * AgentScope 状态表可能建在 public，也可能建在 agentscope（驱动默认 schema），两处都补 updated_at 索引。
--   * 索引是并发建还是普通建：数据量大时建议改用 CREATE INDEX CONCURRENTLY（不能在事务块内执行）。

CREATE INDEX IF NOT EXISTS idx_agent_turn_events_created_at
    ON public.agent_turn_events(created_at);

CREATE INDEX IF NOT EXISTS idx_agent_subagent_runs_created_at
    ON public.agent_subagent_runs(created_at);

CREATE INDEX IF NOT EXISTS idx_asset_outbox_status_updated_at
    ON public.asset_outbox(status, updated_at);

DO $$
BEGIN
    IF to_regclass('public.agentscope_sessions') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_agentscope_sessions_updated
            ON public.agentscope_sessions(updated_at DESC);
    END IF;
    IF to_regclass('agentscope.agentscope_sessions') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_agentscope_sessions_updated
            ON agentscope.agentscope_sessions(updated_at DESC);
    END IF;
END $$;
