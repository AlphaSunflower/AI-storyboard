ALTER TABLE public.storyboard_shot_records
    ADD COLUMN IF NOT EXISTS project_scene_id TEXT;

CREATE INDEX IF NOT EXISTS idx_storyboard_shot_records_project_scene
    ON public.storyboard_shot_records(project_scene_id);
