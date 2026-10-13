-- 追加模型种子（幂等）：豆包 Seedream 5.0 Flash -> 豆包渠道
-- 由 04-seed-models.sh 在还原转储后执行；对运行中的库也可重复执行：
--   docker exec -i aistoryboard-postgres psql -U postgres -d llm_gateway -f - < db/sql/seed-models.sql
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

  IF to_regclass('public.model_params') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM public.model_params WHERE model_name = v_model) THEN
    INSERT INTO public.model_params
      (id, model_name, type, n_min, n_max, n_default, created_at, updated_at, is_default)
    VALUES
      (v_params_id, v_model, 'image', 1, 10, 1, now(), now(), false);
    RAISE NOTICE '[seed] 已插入 model_params: %', v_model;
  END IF;

  -- 渠道 models 列表里补上（管理后台展示用，路由本身不依赖该列）
  UPDATE public.channel
     SET models = CASE WHEN coalesce(models, '') = '' THEN v_model ELSE models || ',' || v_model END,
         updated_at = now()
   WHERE id = v_channel
     AND coalesce(models, '') NOT LIKE '%' || v_model || '%';
END $$;

COMMIT;
