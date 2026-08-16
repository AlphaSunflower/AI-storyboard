-- V7__ai_asset_library.sql
-- AI 资产库：人物/道具/场景资产（多图 + 文字约束），项目级 + 用户全局级
-- 生成时：资产文字卡约束分镜脚本，资产图/文字卡注入视频与图片生成（跨分镜一致性）

CREATE TABLE IF NOT EXISTS assets (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id     TEXT NOT NULL,            -- 归属用户
    project_id  TEXT REFERENCES projects(id) ON DELETE CASCADE,  -- null=用户全局资产库；非空=项目资产库
    type        TEXT NOT NULL,            -- character / prop / scene
    name        TEXT NOT NULL,            -- 资产名（如「阿伟」）
    description TEXT,                     -- 文字约束（外貌/外观/构成，生成时注入）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_assets_user_project ON assets(user_id, project_id);

CREATE TABLE IF NOT EXISTS asset_images (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    asset_id    TEXT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    url         TEXT NOT NULL,            -- /api/files/images/xxx.png
    sort_order  INTEGER NOT NULL DEFAULT 0,  -- 主图 = 最小 sort_order
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asset_images_asset ON asset_images(asset_id, sort_order);

CREATE TABLE IF NOT EXISTS scene_assets (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    scene_id    TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    asset_id    TEXT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_scene_assets_scene ON scene_assets(scene_id);
CREATE INDEX IF NOT EXISTS idx_scene_assets_asset ON scene_assets(asset_id);
