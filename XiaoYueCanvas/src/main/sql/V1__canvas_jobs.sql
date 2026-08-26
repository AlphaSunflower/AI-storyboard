-- canvas_jobs：Morrow 设计助理作业表（ai_storyboard 共享库），psql 手动应用
-- 沿用库约定：id/conversation_id/result_asset_id 用 text（非 uuid），与 conversations/agent_assets 一致
CREATE TABLE IF NOT EXISTS public.canvas_jobs (
    id                  text PRIMARY KEY,
    client_request_id   text UNIQUE NOT NULL,          -- 幂等键
    user_id             text NOT NULL,                 -- 作业属主（JWT userId）
    conversation_id     text,                          -- 归属会话（可为空）
    project_id          varchar(64),
    capability_id       varchar(64) NOT NULL,
    node_ids            text,                          -- JSON TEXT
    selected_node_id    varchar(64),
    source_asset_ids    text,                          -- JSON TEXT
    params              text,                          -- JSON TEXT（prompt/model/resolution/duration/...）
    status              varchar(16) NOT NULL DEFAULT 'queued',  -- queued/running/completed/failed/cancelled
    progress            int NOT NULL DEFAULT 0,
    error               text,
    result_asset_id     text,                          -- → agent_assets.id
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_canvas_jobs_conversation ON public.canvas_jobs(conversation_id);
CREATE INDEX IF NOT EXISTS idx_canvas_jobs_status ON public.canvas_jobs(status);
