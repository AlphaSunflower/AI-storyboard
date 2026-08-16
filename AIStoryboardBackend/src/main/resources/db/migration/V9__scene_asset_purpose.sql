-- 分镜↔资产关联区分用途：图片生成只注入 image，视频生成只注入 video（避免互相污染）
ALTER TABLE public.scene_assets ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'image';
