-- 追加模型种子（幂等）：豆包 Seedream 5.0 Flash -> 豆包渠道
-- 由 04-seed-models.sh 在还原转储后执行；对运行中的库也可重复执行：
--   docker exec -i aistoryboard-postgres psql -U postgres -d llm_gateway -f - < db/sql/seed-models.sql
--
-- Seedream 5.0 flash 是「单图模型」：方舟只接受文生图/单图生图/多参考图生图，不接受
-- sequential_image_generation（连 disabled 都拒绝）、sequential_image_generation_options 和
-- stream，尺寸档位仅 1K/1.5K/2K，参考图最多 10 张。参数能力见方舟「图片生成 API」文档。
-- 因此这里把 model_params 收敛为单图能力，避免前端仍给出多图/4K 选项后打到方舟 400。
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
  v_model     text := 'doubao-seedream-5-0-flash-260915';
  v_channel   text;
  v_route_id  text := 'd0ffaba2dadb98b4ee3bae67c3f0aeb1';
  v_params_id text := '23bf3dba1b777606f2f72c6d4e1e4396';
BEGIN
  IF to_regclass('public.model_route') IS NULL OR to_regclass('public.channel') IS NULL THEN
    RAISE NOTICE '[seed] 目标表不存在（尚未还原转储），跳过';
    RETURN;
  END IF;

  -- 优先按渠道名，其次按方舟 base_url 兜底
  SELECT id INTO v_channel
    FROM public.channel
   WHERE deleted = false
     AND (name = '豆包生成' OR coalesce(base_url, '') LIKE '%ark.cn-beijing.volces.com%')
   ORDER BY CASE WHEN name = '豆包生成' THEN 0 ELSE 1 END, priority NULLS LAST, created_at
   LIMIT 1;

  IF v_channel IS NULL THEN
    RAISE NOTICE '[seed] 未找到豆包渠道，跳过 %', v_model;
    RETURN;
  END IF;

  IF EXISTS (SELECT 1 FROM public.model_route WHERE model_name = v_model) THEN
    RAISE NOTICE '[seed] model_route 已存在: %', v_model;
  ELSE
    INSERT INTO public.model_route
      (id, model_name, channel_id, default_params, created_at, updated_at, deleted, type, is_default)
    VALUES
      (v_route_id, v_model, v_channel, NULL, now(), now(), false, 'image', false);
    RAISE NOTICE '[seed] 已插入 model_route: % -> channel %', v_model, v_channel;
  END IF;

  IF to_regclass('public.model_params') IS NOT NULL THEN
    IF EXISTS (SELECT 1 FROM public.model_params WHERE model_name = v_model) THEN
      UPDATE public.model_params
         SET n_min = 1,
             n_max = 1,
             n_default = 1,
             sizes = '1K,1.5K,2K',
             size_default = '2K',
             ref_images_min = 1,
             ref_images_max = 10,
             updated_at = now()
       WHERE model_name = v_model;
      RAISE NOTICE '[seed] 已收敛单图能力（n_max=1 / sizes=1K,1.5K,2K / ref_images_max=10）: %', v_model;
    ELSE
      INSERT INTO public.model_params
        (id, model_name, type, n_min, n_max, n_default, sizes, size_default,
         ref_images_min, ref_images_max, max_image_size_mb, max_prompt_chars,
         created_at, updated_at, is_default)
      VALUES
        (v_params_id, v_model, 'image', 1, 1, 1, '1K,1.5K,2K', '2K',
         1, 10, 30, 300, now(), now(), false);
      RAISE NOTICE '[seed] 已插入 model_params: %', v_model;
    END IF;
  END IF;

  -- 渠道 models 列表里补上（管理后台展示用，路由本身不依赖该列）
  UPDATE public.channel
     SET models = CASE WHEN coalesce(models, '') = '' THEN v_model ELSE models || ',' || v_model END,
         updated_at = now()
   WHERE id = v_channel
     AND coalesce(models, '') NOT LIKE '%' || v_model || '%';
END $$;

COMMIT;
