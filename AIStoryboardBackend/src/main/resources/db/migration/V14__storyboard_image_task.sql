-- V14__storyboard_image_task.sql
-- 分镜图片队列（/chat Moon 智能体：分镜写库自动配图 + 审查卡完善图片）。
-- 一条记录 = 一个分镜的一次生图/图改图任务（mode=generate/edit）；状态机 queued → running → completed/failed/cancelled。
-- 终态图片 URL/状态由 ImageGenerationServiceImpl 按 sceneId 回写 scenes 表，本表只跟踪队列进度与取消。

CREATE TABLE IF NOT EXISTS storyboard_image_task (
    id               VARCHAR(64) PRIMARY KEY,
    scene_id         VARCHAR(64) NOT NULL,
    project_id       VARCHAR(64),
    conversation_id  VARCHAR(64),
    status           VARCHAR(16) NOT NULL DEFAULT 'queued',
    prompt           TEXT,
    model            VARCHAR(64),
    size             VARCHAR(32),
    quality          VARCHAR(32),
    n                INTEGER NOT NULL DEFAULT 1,
    mode             VARCHAR(16),
    source_image_url TEXT,
    ref_image_urls   TEXT,
    task_id          VARCHAR(64),
    progress         INTEGER NOT NULL DEFAULT 0,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_storyboard_image_task_status_created
    ON storyboard_image_task(status, created_at);
CREATE INDEX IF NOT EXISTS idx_storyboard_image_task_project
    ON storyboard_image_task(project_id);
CREATE INDEX IF NOT EXISTS idx_storyboard_image_task_scene
    ON storyboard_image_task(scene_id);
