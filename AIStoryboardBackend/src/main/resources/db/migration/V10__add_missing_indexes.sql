-- V10__add_missing_indexes.sql
-- 补齐索引：修复慢查询——
--   项目列表 findLatestDraft/findByUserId 按 user_id 全表扫；
--   视频轮询按 video_task_id / image_task_id 全表扫；
--   GET /api/agent/tasks/{taskId} 按 task_id 全表扫。
CREATE INDEX IF NOT EXISTS idx_projects_user ON projects(user_id);
CREATE INDEX IF NOT EXISTS idx_scenes_video_task ON scenes(video_task_id);
CREATE INDEX IF NOT EXISTS idx_scenes_image_task ON scenes(image_task_id);
CREATE INDEX IF NOT EXISTS idx_agent_assets_task ON agent_assets(task_id);
