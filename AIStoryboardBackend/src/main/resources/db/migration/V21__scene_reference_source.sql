-- 分镜参考素材来源：upload=用户直接上传 / output=项目产出素材 / scene_image=当前分镜生成图
ALTER TABLE public.scene_reference_images
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(24) NOT NULL DEFAULT 'upload',
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_conversation_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ref_images_scene_source
    ON public.scene_reference_images(scene_id, source_type, sort_order);
