-- 图片模型比例能力：model_params.aspect_ratios 原先只给视频行配置，图片行留空时
-- 前端「生成偏好」只有「智能」可选（交给智能体决定），比例全都选不了。
-- 这里给所有 image 类型模型补一份默认比例集合；已由管理员配置过的模型原样保留
-- （COALESCE + NULLIF），某个模型实际不支持其中某项时，在「模型参数」页删掉该值即可。
--
-- 注意：网关未引入 Flyway，本文件为留档，需手动 psql 执行：
--   psql -U postgres -d llm_gateway -f AILLMGateway/src/main/resources/db/migration/V17__image_aspect_ratios.sql
UPDATE model_params
SET aspect_ratios = COALESCE(NULLIF(aspect_ratios, ''), '21:9,16:9,4:3,1:1,3:4,9:16'),
    aspect_ratio_default = COALESCE(NULLIF(aspect_ratio_default, ''), '1:1'),
    updated_at = NOW()
WHERE type = 'image';
