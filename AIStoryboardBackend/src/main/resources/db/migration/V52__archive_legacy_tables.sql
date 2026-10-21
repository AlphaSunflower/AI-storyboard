-- V52: 归档已退役的旧编排/任务表（不直接 DROP，观察一版后再清理）
--
-- 背景：
--   1. agent_loop_* / agent_handoffs / agent_task_* 来自 2026-09-26 前的旧编排引擎，
--      代码已删除（OrchestrationRunRecorder、AgentTaskPlanController 等）。
--   2. scenes_backup_pre_v15 是 V15 拆表前的手工备份。
--   3. public.agentscope_sessions 是 distributed-store 关闭时的 fallback；
--      当前实际使用 agentscope.agentscope_sessions，这里只清空陈旧状态，不删表。
--
-- 旧资产表 assets/asset_images 的归档拆到 V53，等后端重启后再执行，
-- 避免仍在运行的旧后端 getProjectAssets 读到已改名的表。
--
-- 手动执行（项目未接 Flyway 的 db/migration 目录）：
--   psql -h 127.0.0.1 -U postgres -d ai_storyboard -f V52__archive_legacy_tables.sql
--
-- 回滚：
--   ALTER TABLE public.agent_loop_runs_archive RENAME TO agent_loop_runs;  ...

BEGIN;

-- 子表先改名，避免外键约束名混淆；父表改名后外键仍指向父表 OID，约束继续有效。
ALTER TABLE IF EXISTS public.agent_handoffs       RENAME TO agent_handoffs_archive;
ALTER TABLE IF EXISTS public.agent_loop_steps     RENAME TO agent_loop_steps_archive;
ALTER TABLE IF EXISTS public.agent_loop_runs      RENAME TO agent_loop_runs_archive;
ALTER TABLE IF EXISTS public.agent_task_edges     RENAME TO agent_task_edges_archive;
ALTER TABLE IF EXISTS public.agent_task_nodes     RENAME TO agent_task_nodes_archive;
ALTER TABLE IF EXISTS public.agent_task_plans     RENAME TO agent_task_plans_archive;

-- 手工备份快照。
ALTER TABLE IF EXISTS public.scenes_backup_pre_v15 RENAME TO scenes_backup_pre_v15_archive;

COMMIT;

-- 清理 4.1 MB 陈旧运行时状态；表保留作为 distributed-store=false 的 fallback。
DO $$
BEGIN
    IF to_regclass('public.agentscope_sessions') IS NOT NULL THEN
        TRUNCATE TABLE public.agentscope_sessions;
    END IF;
END $$;

-- 验证：以下查询应返回 0 行。
-- SELECT tablename FROM pg_tables WHERE schemaname='public'
--   AND tablename IN ('agent_handoffs','agent_loop_runs','agent_loop_steps',
--                     'agent_task_plans','agent_task_nodes','agent_task_edges',
--                     'scenes_backup_pre_v15');
