ALTER TABLE public.storyboard_runs
    ADD COLUMN IF NOT EXISTS requested_shot_count TEXT NOT NULL DEFAULT '';
