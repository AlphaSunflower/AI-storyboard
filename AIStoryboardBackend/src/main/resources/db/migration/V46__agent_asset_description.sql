-- 素材语义描述（2026-10-17）
--
-- 背景：上传的参考素材在智能体眼里此前只有一个标签「会话图片」（SessionAssetService.toRef
-- 只读 purpose/prompt，兜底固定文案，从不读 original_name），模型无法判断哪张是角色定妆、
-- 哪张是场景参考，因此「按剧本把对应资产绑到分镜」无从谈起。
--
-- 本列由 AssetCaptionService 在上传后异步调用视觉模型写入，形如：
--   「角色｜林知夏：短发女性，黑色风衣，站在雨夜仓库门口」
-- SessionAssetService 的 label 优先读它。
ALTER TABLE agent_assets ADD COLUMN IF NOT EXISTS description TEXT;

COMMENT ON COLUMN agent_assets.description IS '视觉模型生成的素材语义描述（角色/场景/道具 + 一句话），供分镜按剧情绑定资产';
