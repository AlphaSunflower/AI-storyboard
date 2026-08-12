-- V6__scene_params_and_refs.sql
-- 分镜生成参数覆盖（null = 跟随全局默认）+ 多图结果
ALTER TABLE scenes
    ADD COLUMN image_urls TEXT,
    ADD COLUMN image_model VARCHAR(128),
    ADD COLUMN image_size VARCHAR(64),
    ADD COLUMN image_quality VARCHAR(32),
    ADD COLUMN image_n INT,
    ADD COLUMN video_model VARCHAR(128),
    ADD COLUMN video_aspect_ratio VARCHAR(32);

-- 参考素材表通用化（原 scene_reference_images 基本未用：加类型/文件名/大小，image_url 列复用存相对 URL）
ALTER TABLE scene_reference_images
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'image',
    ADD COLUMN file_name VARCHAR(255),
    ADD COLUMN file_size BIGINT;
