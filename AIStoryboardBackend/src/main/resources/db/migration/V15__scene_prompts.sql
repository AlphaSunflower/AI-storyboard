-- V15__scene_prompts.sql
-- 分镜宽表纵向拆分：把编排/prompt/参数等低频宽列从热路径 scenes 剥离到 1:1 scene_prompts。
-- 目标：生成轮询/状态回写只写 scenes 基础列（id/project/scene_number/状态/任务/URL），
--       不再整行重写 prompt 大文本，降低行宽与写放大。对外 SceneResponse / 内部 Scene DTO JSON 形状不变。
-- 迁移是一次性应用（生产低峰、事务内）；回滚：把 scene_prompts 列合并回 scenes 前先备份。

BEGIN;

-- 1) 建 1:1 明细表（PK=scene_id，FK 级联删与 scenes 同 lifecycle）
CREATE TABLE IF NOT EXISTS scene_prompts (
    scene_id            TEXT PRIMARY KEY REFERENCES scenes(id) ON DELETE CASCADE,
    script_content      TEXT,
    image_prompt        TEXT,
    video_prompt        TEXT,
    negative_prompt     TEXT,
    camera_movement     TEXT,
    shot_type           TEXT,
    sound_design        TEXT,
    ai_model            TEXT,
    video_resolution    TEXT,
    duration            INTEGER,
    image_model         VARCHAR(128),
    image_size          VARCHAR(64),
    image_quality       VARCHAR(32),
    image_n             INT,
    video_model         VARCHAR(128),
    video_aspect_ratio  VARCHAR(32)
);

-- 2) 回填存量数据（scene_prompts.scene_id 与 scenes.id 一一对应）
INSERT INTO scene_prompts (
    scene_id, script_content, image_prompt, video_prompt, negative_prompt,
    camera_movement, shot_type, sound_design, ai_model, video_resolution, duration,
    image_model, image_size, image_quality, image_n, video_model, video_aspect_ratio
)
SELECT
    id, script_content, image_prompt, video_prompt, negative_prompt,
    camera_movement, shot_type, sound_design, ai_model, video_resolution, duration,
    image_model, image_size, image_quality, image_n, video_model, video_aspect_ratio
FROM scenes
ON CONFLICT (scene_id) DO NOTHING;

-- 3) 从 scenes 删除已迁移的宽列（保留基础列）
ALTER TABLE scenes
    DROP COLUMN IF EXISTS script_content,
    DROP COLUMN IF EXISTS image_prompt,
    DROP COLUMN IF EXISTS video_prompt,
    DROP COLUMN IF EXISTS negative_prompt,
    DROP COLUMN IF EXISTS camera_movement,
    DROP COLUMN IF EXISTS shot_type,
    DROP COLUMN IF EXISTS sound_design,
    DROP COLUMN IF EXISTS ai_model,
    DROP COLUMN IF EXISTS video_resolution,
    DROP COLUMN IF EXISTS duration,
    DROP COLUMN IF EXISTS image_model,
    DROP COLUMN IF EXISTS image_size,
    DROP COLUMN IF EXISTS image_quality,
    DROP COLUMN IF EXISTS image_n,
    DROP COLUMN IF EXISTS video_model,
    DROP COLUMN IF EXISTS video_aspect_ratio;

-- scene_prompts.scene_id 已由 PK 索引，无需额外索引；列宽小、按 scene_id 点查。
COMMIT;
