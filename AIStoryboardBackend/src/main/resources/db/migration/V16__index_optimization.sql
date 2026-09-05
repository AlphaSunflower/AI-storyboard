-- V16__index_optimization.sql
-- 补齐/清理主后端索引，对齐实际查询形状（慢查询修复延续 V10）。
BEGIN;

-- projects：列表/草稿恢复按 user_id + updated_at 排序；单列 user_id 索引无法覆盖排序。
DROP INDEX IF EXISTS idx_projects_user;
CREATE INDEX IF NOT EXISTS idx_projects_user_updated
    ON projects(user_id, updated_at DESC);
-- findLatestDraft：只查 draft，用部分索引更窄。
CREATE INDEX IF NOT EXISTS idx_projects_user_draft
    ON projects(user_id, updated_at DESC) WHERE status = 'draft';

-- agent_assets：生成记录列表 conversation_id IN (...) ORDER BY created_at DESC，
-- 现有 (conversation_id, type) 不含 created_at，需补排序列。
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv_created
    ON agent_assets(conversation_id, created_at DESC);

-- assets：findByProjectOrGlobal 走 project_id = ? OR (project_id IS NULL AND user_id = ?)，
-- 现有 (user_id, project_id) 前导 user_id 无法覆盖 project_id 分支。
CREATE INDEX IF NOT EXISTS idx_assets_project
    ON assets(project_id);
CREATE INDEX IF NOT EXISTS idx_assets_user_global
    ON assets(user_id) WHERE project_id IS NULL;

COMMIT;
