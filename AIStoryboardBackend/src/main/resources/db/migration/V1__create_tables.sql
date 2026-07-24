-- V1__create_tables.sql
-- 注意：users 表已存在于 newworkflow.public，此处不创建
-- 仅创建 AI 分镜业务表

CREATE TABLE IF NOT EXISTS projects (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL DEFAULT '未命名项目',
    description     TEXT,
    creation_type   TEXT NOT NULL DEFAULT 'movie',
    custom_type_desc TEXT,
    aspect_ratio    TEXT NOT NULL DEFAULT '16:9',
    reference_image_url TEXT,
    script_text     TEXT,
    ai_model        TEXT NOT NULL DEFAULT 'gemini-3-flash-preview',
    status          TEXT NOT NULL DEFAULT 'draft',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scenes (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    project_id      TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    scene_number    INTEGER NOT NULL,
    script_content  TEXT,
    image_prompt    TEXT,
    video_prompt    TEXT,
    negative_prompt TEXT,
    camera_movement TEXT,
    shot_type       TEXT,
    sound_design    TEXT,
    ai_model        TEXT,
    video_resolution TEXT,
    duration        INTEGER,
    image_url       TEXT,
    video_url       TEXT,
    image_status    TEXT NOT NULL DEFAULT 'pending',
    video_status    TEXT NOT NULL DEFAULT 'pending',
    image_task_id   TEXT,
    video_task_id   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scenes_project ON scenes(project_id, scene_number);

CREATE TABLE IF NOT EXISTS scene_reference_images (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    scene_id    TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    image_url   TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ref_images_scene ON scene_reference_images(scene_id, sort_order);
