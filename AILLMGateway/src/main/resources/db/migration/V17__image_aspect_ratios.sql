-- 图片模型比例能力：model_params.aspect_ratios 原先只给视频行配置，图片行留空时
-- 前端「生成偏好」只提供「智能」（交给智能体决定）。这里给已知图片模型补默认比例集合，
-- 未配置的模型保持空 → 前端仅「智能」可选。
UPDATE model_params
SET aspect_ratios = COALESCE(NULLIF(aspect_ratios, ''), '21:9,16:9,4:3,1:1,3:4,9:16'),
    aspect_ratio_default = COALESCE(NULLIF(aspect_ratio_default, ''), '1:1'),
    updated_at = NOW()
WHERE type = 'image'
  AND model_name IN (
      'doubao-seedream-4-0-250828',
      'doubao-seedream-4-5-251128'
  );
