-- 分镜来源对话：由 MoonAgent 写库时记录，生成中心按当前对话筛选分镜。
ALTER TABLE public.scenes
    ADD COLUMN IF NOT EXISTS conversation_id TEXT;

CREATE INDEX IF NOT EXISTS idx_scenes_project_conversation_number
    ON public.scenes(project_id, conversation_id, scene_number);

-- 历史回填：已有生成记录能明确 scene_id → conversation_id 的分镜恢复来源对话。
UPDATE public.scenes AS scene
SET conversation_id = source.conversation_id
FROM (
    SELECT scene_id, MIN(conversation_id) AS conversation_id
    FROM public.agent_assets
    WHERE scene_id IS NOT NULL
      AND conversation_id IS NOT NULL
    GROUP BY scene_id
) AS source
WHERE scene.id = source.scene_id
  AND scene.conversation_id IS NULL;
