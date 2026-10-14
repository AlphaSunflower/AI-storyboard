-- 图片默认模型统一为 doubao-seedream-4-0-250828。
--
-- 背景：默认模型有两个开关，容易走岔——
--   model_route.is_default（同步到 Redis gateway:default_model:<type>，决定实际按哪个模型生成）
--   model_params.is_default（/v1/models 列表里标记哪个模型是「默认」，前端下拉据此预选）
-- 现状是 Redis / model_route 已是 Seedream，而 model_params 还停在 gpt-image-2，
-- 于是前端把 gpt-image-2 当默认。这里把两个开关对齐。
--
-- 注意：网关未引入 Flyway，本文件为留档，需手动 psql 执行：
--   psql -U postgres -d llm_gateway -f AILLMGateway/src/main/resources/db/migration/V19__default_image_model_seedream.sql

-- 1) 列表标记（前端预选依据）
UPDATE model_params
SET is_default = false, updated_at = NOW()
WHERE type = 'image' AND is_default = true;

UPDATE model_params
SET is_default = true, updated_at = NOW()
WHERE type = 'image' AND model_name = 'doubao-seedream-4-0-250828';

-- 2) 实际生成用的默认路由（Redis 由网关在路由变更时同步；这里同时纠正库里的标记）
UPDATE model_route
SET is_default = false, updated_at = NOW()
WHERE type = 'image' AND is_default = true;

UPDATE model_route
SET is_default = true, updated_at = NOW()
WHERE type = 'image'
  AND deleted = false
  AND model_name = 'doubao-seedream-4-0-250828';

-- 3) 清掉网关的模型列表缓存，避免最长 60s 内仍下发旧默认标记
--    （Redis 不可用时可忽略，缓存会自然过期）
--    redis-cli DEL gateway:models:image gateway:models:all
