-- V17__agent_asset_purpose.sql
-- Moon 聊天上传素材用途（每个素材写清它是干什么的）：
--   purpose: 用户填写的用途/说明（如「人物参考（锁脸）」「场景参考」）
--   kind:    reference=无文字约束的普通参考图（需整理智能体视觉映射）| asset=带文字约束的资产（无需整理）
ALTER TABLE public.agent_assets
    ADD COLUMN IF NOT EXISTS purpose TEXT,
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'reference';
