-- Durable idempotency ledger for server-side canvas graph commands.
-- Apply after V7 in environments that manually manage XiaoYueCanvas migrations.
CREATE TABLE IF NOT EXISTS public.canvas_graph_operations (
    operation_key text PRIMARY KEY,
    canvas_id     text NOT NULL REFERENCES public.morrow_canvases(id) ON DELETE CASCADE,
    user_id       text NOT NULL,
    project_id    text NOT NULL,
    run_id        text,
    command_name  text NOT NULL,
    status        varchar(24) NOT NULL DEFAULT 'running',
    result_json   text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT canvas_graph_operations_status_check
        CHECK (status IN ('running', 'completed', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_canvas_graph_operations_canvas
    ON public.canvas_graph_operations(canvas_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_canvas_graph_operations_run
    ON public.canvas_graph_operations(run_id, created_at DESC)
    WHERE run_id IS NOT NULL;
