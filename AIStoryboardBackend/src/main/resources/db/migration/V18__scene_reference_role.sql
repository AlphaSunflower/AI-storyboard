-- V18__scene_reference_role.sql
-- 分镜参考素材增加「参考角色」：FIRST_FRAME / LAST_FRAME / FIRST_LAST_FRAME / FULL_REFERENCE / REFERENCE
ALTER TABLE public.scene_reference_images
    ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'REFERENCE';
