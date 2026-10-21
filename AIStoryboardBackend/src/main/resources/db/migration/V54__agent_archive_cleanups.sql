-- 已归档会话清理台账（2026-10-21）
--
-- 每个已归档会话清理成功后写一行，保证「一个归档会话只清一次」：
-- 没有这张表时，定时任务无法区分「已清过」和「还没清」，会每天对同一批会话
-- 重复做删除 + 清 AgentScope 状态 + 清沙箱的无效动作。
--
-- 清理只删运行时派生数据（轮次事件、checkpoint、画布运行、文档索引、异步任务、
-- 工作流运行），消息、产出素材行与 MinIO 图片/文档对象一律保留。
--
-- 项目未接 Flyway，需手动执行：
--   psql -h <host> -p 5432 -U postgres -d <db> -f V54__agent_archive_cleanups.sql

CREATE TABLE IF NOT EXISTS public.agent_archive_cleanups (
    conversation_id TEXT PRIMARY KEY REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id         TEXT,
    deleted_rows    INTEGER NOT NULL DEFAULT 0,
    detail          TEXT,
    cleaned_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_archive_cleanups_cleaned_at
    ON public.agent_archive_cleanups(cleaned_at DESC);

COMMENT ON TABLE public.agent_archive_cleanups IS
    '已归档会话清理台账：每个会话只清一次，行随 conversations 级联删除';
