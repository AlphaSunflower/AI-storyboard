-- 产出素材按对话归档：一个对话对应「产出素材」里的一个文件夹。
-- 原始素材（上传/导入）不带对话归属，仍按项目库目录管理。

ALTER TABLE asset.library_assets
    ADD COLUMN IF NOT EXISTS source_conversation_id TEXT;

CREATE INDEX IF NOT EXISTS idx_library_assets_conversation
    ON asset.library_assets (owner_user_id, owner_project_id, source_conversation_id, created_at DESC)
    WHERE source_conversation_id IS NOT NULL;

-- 历史产出回填：MoonAgent 的 library_assets.source_id 就是 agent_assets.id。
DO $$
BEGIN
    IF to_regclass('public.agent_assets') IS NOT NULL THEN
        UPDATE asset.library_assets a
        SET source_conversation_id = aa.conversation_id
        FROM public.agent_assets aa
        WHERE a.source_conversation_id IS NULL
          AND a.source_type IN ('generation', 'canvas-generation')
          AND a.source_id = aa.id
          AND aa.conversation_id IS NOT NULL;
    END IF;
END $$;
