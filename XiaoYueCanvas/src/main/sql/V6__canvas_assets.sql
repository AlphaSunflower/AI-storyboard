-- V6__canvas_assets.sql
-- Canonical canvas asset mapping: MinIO object <-> canvas/node/generation record.
-- XiaoYueCanvas does not use Flyway; apply this file with psql after V5.

BEGIN;

CREATE TABLE IF NOT EXISTS public.canvas_assets (
    id                    text PRIMARY KEY,
    canvas_id             text NOT NULL REFERENCES public.morrow_canvases(id) ON DELETE CASCADE,
    project_id            text,
    user_id               text NOT NULL,
    type                  varchar(16) NOT NULL,
    mime_type             varchar(128) NOT NULL,
    object_url            text NOT NULL,
    object_key            text NOT NULL,
    generation_record_id  text,
    producer_node_id      text,
    status                varchar(24) NOT NULL DEFAULT 'ready',
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT canvas_assets_type_check CHECK (type IN ('image', 'video', 'audio')),
    CONSTRAINT canvas_assets_status_check CHECK (status IN ('pending', 'ready', 'failed'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canvas_assets_object
    ON public.canvas_assets(canvas_id, object_url);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canvas_assets_generation
    ON public.canvas_assets(generation_record_id, object_url)
    WHERE generation_record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canvas_assets_canvas
    ON public.canvas_assets(canvas_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_canvas_assets_node
    ON public.canvas_assets(canvas_id, producer_node_id);

COMMIT;
