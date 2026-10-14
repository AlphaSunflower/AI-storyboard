-- 图片比例能力补齐：V17 只覆盖了 Seedream 4.0 / 4.5，其余 image 类型模型仍为空，
-- 前端「生成偏好」因此只有「智能」可选。这里给所有 image 模型补默认比例集合；
-- 已由管理员配置过比例的模型保持不变（COALESCE + NULLIF），
-- 若某个模型实际不支持其中某项，可在「模型参数」页删掉该值。
UPDATE model_params
SET aspect_ratios = COALESCE(NULLIF(aspect_ratios, ''), '21:9,16:9,4:3,1:1,3:4,9:16'),
    aspect_ratio_default = COALESCE(NULLIF(aspect_ratio_default, ''), '1:1'),
    updated_at = NOW()
WHERE type = 'image';
