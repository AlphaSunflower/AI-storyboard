-- V3: agent_assets.url 改为可空
-- 原因：视频资产在任务创建时（status=queued）尚无生成结果 URL，
-- 只有轮询完成后才写入；图片/参考图资产则在插入时即有 URL。
-- V2 中 url TEXT NOT NULL 导致"无 sceneId 的 Agent 视频"落库必失败。
ALTER TABLE agent_assets ALTER COLUMN url DROP NOT NULL;
