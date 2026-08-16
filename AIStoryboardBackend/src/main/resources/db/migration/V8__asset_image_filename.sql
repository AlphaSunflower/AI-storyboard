-- V8__asset_image_filename.sql
-- 资产图片存上传原始文件名（DepthCarousel 展示当前图片名用）
ALTER TABLE asset_images ADD COLUMN IF NOT EXISTS file_name VARCHAR(255);
