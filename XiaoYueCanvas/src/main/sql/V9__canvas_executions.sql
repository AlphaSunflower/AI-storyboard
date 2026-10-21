-- V9__canvas_executions.sql
-- 画布「执行分组」批次：执行器从浏览器搬到服务端（XiaoYueCanvas 无 Flyway，psql 手动应用）。
--
-- 背景：以前分组执行是浏览器里跑 DAG，刷新/HMR/断网就把批次打断（用户看到「只生成了第一个节点」）。
-- 现在批次与每个节点的状态落库，由服务端串行推进；浏览器只读快照，刷新后接着看进度。
BEGIN;

CREATE TABLE IF NOT EXISTS canvas_executions (
    id            TEXT PRIMARY KEY,
    canvas_id     TEXT        NOT NULL,
    user_id       TEXT        NOT NULL,
    status        TEXT        NOT NULL,          -- queued / running / succeeded / failed / cancelled
    max_parallel  INT         NOT NULL DEFAULT 1,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_canvas_executions_canvas
    ON canvas_executions(canvas_id, created_at DESC);

CREATE TABLE IF NOT EXISTS canvas_execution_nodes (
    id                   TEXT PRIMARY KEY,
    execution_id         TEXT        NOT NULL REFERENCES canvas_executions(id) ON DELETE CASCADE,
    position             INT         NOT NULL,
    node_id              TEXT        NOT NULL,
    action_id            TEXT        NOT NULL,
    capability_id        TEXT        NOT NULL,
    client_request_id    TEXT        NOT NULL,
    params               TEXT,
    source_asset_ids     TEXT,
    result_slot_node_id  TEXT,
    status               TEXT        NOT NULL,   -- pending / running / succeeded / failed / blocked / cancelled
    job_id               TEXT,
    result_asset_ids     TEXT,
    error                TEXT,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_canvas_execution_nodes_position
    ON canvas_execution_nodes(execution_id, position);

CREATE INDEX IF NOT EXISTS idx_canvas_execution_nodes_status
    ON canvas_execution_nodes(execution_id, status);

COMMIT;
