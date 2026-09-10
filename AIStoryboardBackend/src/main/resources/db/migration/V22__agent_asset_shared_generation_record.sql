-- 生成记录公共归属：AI 分镜服务可直接写入 agent_assets（conversation_id 可为空），
-- 通过 user_id + project_id 参与用户级生成记录与项目产出素材聚合。

ALTER TABLE public.agent_assets
    ADD COLUMN IF NOT EXISTS user_id TEXT,
    ADD COLUMN IF NOT EXISTS project_id TEXT REFERENCES public.projects(id) ON DELETE CASCADE;

UPDATE public.agent_assets aa
SET user_id = c.user_id,
    project_id = c.project_id
FROM public.conversations c
WHERE aa.conversation_id = c.id
  AND (aa.user_id IS NULL OR aa.project_id IS NULL);

CREATE INDEX IF NOT EXISTS idx_agent_assets_user_created
    ON public.agent_assets(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_assets_project_type_created
    ON public.agent_assets(project_id, type, created_at DESC);

-- 回填历史上只在 scenes 表中落库的 AI 分镜生成结果；
-- 同一 scene + type 已有生成记录时不重复插入（Moon Agent 分镜队列记录优先保留）。
INSERT INTO public.agent_assets (
    conversation_id, user_id, project_id, type, url, status, gen_type,
    result_urls, scene_id, created_at
)
SELECT NULL,
       p.user_id,
       s.project_id,
       'image',
       COALESCE(NULLIF(s.image_url, ''), NULLIF(split_part(s.image_urls, ',', 1), '')),
       'completed',
       'storyboard_image',
       CASE
           WHEN s.image_urls IS NOT NULL AND btrim(s.image_urls) <> ''
               THEN to_json(string_to_array(s.image_urls, ','))::text
           ELSE json_build_array(s.image_url)::text
       END,
       s.id,
       s.updated_at
FROM public.scenes s
JOIN public.projects p ON p.id = s.project_id
WHERE COALESCE(NULLIF(s.image_url, ''), NULLIF(split_part(s.image_urls, ',', 1), '')) IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM public.agent_assets aa
      WHERE aa.scene_id = s.id AND aa.type = 'image'
  );

INSERT INTO public.agent_assets (
    conversation_id, user_id, project_id, type, url, status, gen_type,
    result_urls, scene_id, created_at
)
SELECT NULL,
       p.user_id,
       s.project_id,
       'video',
       s.video_url,
       'completed',
       'storyboard_video',
       json_build_array(s.video_url)::text,
       s.id,
       s.updated_at
FROM public.scenes s
JOIN public.projects p ON p.id = s.project_id
WHERE s.video_url IS NOT NULL AND btrim(s.video_url) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM public.agent_assets aa
      WHERE aa.scene_id = s.id AND aa.type = 'video'
  );
