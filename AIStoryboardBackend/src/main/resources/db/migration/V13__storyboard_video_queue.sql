-- V13__storyboard_video_queue.sql
-- 分镜视频批量生成队列（/chat Moon 智能体「分镜审查卡片 → 全自动生成 → 串行执行」）。
-- 一条记录 = 一个分镜的一个待生成视频任务；状态机 queued → running → completed/failed/cancelled。
-- 终态视频 URL/状态回写 scenes 表（复用 VideoPollExecutor 按 videoTaskId 反查），本表只跟踪队列进度与取消。

CREATE TABLE IF NOT EXISTS storyboard_video_queue (
    id               VARCHAR(64) PRIMARY KEY,
    scene_id         VARCHAR(64) NOT NULL,
    project_id       VARCHAR(64),
    conversation_id  VARCHAR(64),
    status           VARCHAR(16) NOT NULL DEFAULT 'queued',
    prompt           TEXT,
    model            VARCHAR(64),
    resolution       VARCHAR(32),
    size             VARCHAR(32),
    aspect_ratio     VARCHAR(16),
    duration         INTEGER,
    negative_prompt  TEXT,
    source_image_url TEXT,
    ref_image_urls   TEXT,
    ref_asset_ids    TEXT,
    task_id          VARCHAR(64),
    progress         INTEGER NOT NULL DEFAULT 0,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_storyboard_video_queue_status_created
    ON storyboard_video_queue(status, created_at);
CREATE INDEX IF NOT EXISTS idx_storyboard_video_queue_project
    ON storyboard_video_queue(project_id);
CREATE INDEX IF NOT EXISTS idx_storyboard_video_queue_scene
    ON storyboard_video_queue(scene_id);
