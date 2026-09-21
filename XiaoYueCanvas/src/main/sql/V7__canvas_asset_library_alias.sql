-- 项目库真源兼容：保留 canvas_assets.id 作为节点键，并通过别名映射统一资产。
ALTER TABLE public.canvas_assets
    ADD COLUMN IF NOT EXISTS library_asset_id TEXT,
    ADD COLUMN IF NOT EXISTS library_sync_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS uq_canvas_assets_library_alias
    ON public.canvas_assets(canvas_id, library_asset_id)
    WHERE library_asset_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canvas_assets_library_sync
    ON public.canvas_assets(library_sync_state, created_at);
