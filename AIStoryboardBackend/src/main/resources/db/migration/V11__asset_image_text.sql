-- V11__asset_image_text.sql
-- 资产图片级文字约束（每张图携带的 text：人物外貌/道具/场景约束，生成时注入）
-- 与 assets.description（资产级文字卡）互补：description 是资产整体约束，asset_images.text 是单图约束
ALTER TABLE asset_images ADD COLUMN IF NOT EXISTS text TEXT;
