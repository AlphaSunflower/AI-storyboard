-- Morrow 画布元数据：节点实体内容仍存浏览器 IndexedDB（按画布 id = 本表 id）。
-- 项目/画布实体与 ai分镜 项目同一库，会话 project_id 直连共享项目。
CREATE TABLE IF NOT EXISTS morrow_canvases (
    id         text PRIMARY KEY,
    project_id text NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title      text NOT NULL DEFAULT '画布',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_morrow_canvases_project ON morrow_canvases(project_id);
