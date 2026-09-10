-- Seedream 4.0 图片能力：1K/2K/4K，单次 1..15 张，最多 14 张参考图。
UPDATE model_params
SET n_min = 1,
    n_max = 15,
    n_default = 1,
    sizes = '1K,2K,4K',
    size_default = '2K',
    qualities = NULL,
    quality_default = NULL,
    ref_images_min = 1,
    ref_images_max = 14,
    max_image_size_mb = 30,
    max_prompt_chars = 300,
    updated_at = NOW()
WHERE model_name = 'doubao-seedream-4-0-250828'
  AND type = 'image';

-- 方舟渠道补充候选模型列表；无该渠道时保持现状，由管理员按部署环境配置。
UPDATE channel
SET models = CASE
    WHEN models IS NULL OR BTRIM(models) = '' THEN 'doubao-seedream-4-0-250828'
    WHEN POSITION('doubao-seedream-4-0-250828' IN models) > 0 THEN models
    ELSE models || ',doubao-seedream-4-0-250828'
END,
    updated_at = NOW()
WHERE base_url LIKE '%ark.cn-beijing.volces.com%';
