-- V4__morrow_canvas_optimization.sql
-- Morrow 画布索引优化（XiaoYueCanvas 无 Flyway，psql 手动应用）。
BEGIN;

-- morrow_canvases：listByProject 按 project_id + updated_at DESC 排序，现只有单列 project_id 索引。
CREATE INDEX IF NOT EXISTS idx_morrow_canvases_project_updated
    ON morrow_canvases(project_id, updated_at DESC);

-- morrow_canvas_graphs：canvas_id 已是 PK，冗余单列索引删除。
DROP INDEX IF EXISTS idx_morrow_canvas_graphs_canvas;

-- canvas_jobs：代码只按 id / client_request_id(UNIQUE) / result_asset_id 读写，
-- 无按 status/conversation 过滤的查询；确认 pg_stat_user_indexes 无使用后删（后续若新增此类查询需重建）。
DROP INDEX IF EXISTS idx_canvas_jobs_status;
DROP INDEX IF EXISTS idx_canvas_jobs_conversation;

COMMIT;
