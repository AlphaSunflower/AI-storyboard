-- 版本堆叠：生成结果记录来源镜头；同一对话 + 同一镜头的多次生成自动成为一组版本。
ALTER TABLE asset.library_assets
    ADD COLUMN IF NOT EXISTS source_scene_id TEXT;

CREATE INDEX IF NOT EXISTS idx_library_assets_scene_stack
    ON asset.library_assets (owner_user_id, source_conversation_id, source_scene_id)
    WHERE source_scene_id IS NOT NULL;

-- 历史产出回填：library_assets.source_id 就是 agent_assets.id。
DO $$
BEGIN
    IF to_regclass('public.agent_assets') IS NOT NULL THEN
        UPDATE asset.library_assets a
        SET source_scene_id = aa.scene_id
        FROM public.agent_assets aa
        WHERE a.source_scene_id IS NULL
          AND a.source_type IN ('generation', 'canvas-generation')
          AND a.source_id = aa.id
          AND aa.scene_id IS NOT NULL;
    END IF;
END $$;
