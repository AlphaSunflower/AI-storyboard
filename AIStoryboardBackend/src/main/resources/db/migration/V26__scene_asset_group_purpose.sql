ALTER TABLE public.scene_material_group_snapshots
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'video';

CREATE INDEX IF NOT EXISTS idx_scene_material_group_snapshots_group
    ON public.scene_material_group_snapshots(scene_id, group_id, purpose);

ALTER TABLE public.scene_reference_images
    ADD COLUMN IF NOT EXISTS media_id TEXT;
