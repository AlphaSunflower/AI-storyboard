-- Minimal PostgreSQL schema for MoonAgent full-context tests.
-- Mirrors the AIStoryboardBackend Flyway migrations for the agent tables that the
-- context and HITL tests touch. Kept intentionally small: only the tables required
-- by the container-backed integration tests.

CREATE TABLE IF NOT EXISTS public.conversations (
    id                        text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    user_id                   text NOT NULL,
    project_id                text NOT NULL,
    title                     text NOT NULL DEFAULT '新对话',
    status                    text NOT NULL DEFAULT 'active',
    created_at                timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                timestamp with time zone NOT NULL DEFAULT now(),
    canvas_context            text,
    orchestration_mode        character varying(32) NOT NULL DEFAULT 'LEGACY',
    engine_version            character varying(64) NOT NULL DEFAULT 'plan-graph-v1',
    debug_comparison_enabled  boolean NOT NULL DEFAULT true,
    approval_mode             character varying(32) NOT NULL DEFAULT 'PLAN_APPROVAL',
    agentscope_session_initialized_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.agent_messages (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    role             text NOT NULL,
    content          text NOT NULL,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    metadata         jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS public.agent_checkpoints (
    id                     text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id        text NOT NULL,
    action                 text NOT NULL,
    form_token             text NOT NULL,
    plan                   text,
    step                   text,
    status                 text NOT NULL DEFAULT 'pending',
    expiration_time        timestamp with time zone NOT NULL,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    owner_engine           character varying(32) NOT NULL DEFAULT 'LEGACY',
    state_schema_version   integer NOT NULL DEFAULT 1,
    plan_id                text,
    node_id                text,
    runtime_session_id     text,
    runtime_reply_id       text,
    runtime_tool_call_id   text,
    runtime_tool_name      text,
    runtime_tool_calls_json text,
    resume_payload         text,
    updated_at             timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_assets (
    id                   text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id      text,
    type                 text NOT NULL,
    url                  text,
    original_name        text,
    mime_type            character varying(128),
    prompt               text,
    model                text,
    status               text NOT NULL DEFAULT 'queued',
    task_id              text,
    error                text,
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    gen_type             character varying(32),
    params               text,
    reference_urls       text,
    result_urls          text,
    duration_ms          bigint,
    scene_id             character varying(64),
    purpose              text,
    kind                 character varying(16) NOT NULL DEFAULT 'reference',
    user_id              text,
    project_id           text,
    loop_run_id          text,
    chain_run_id         text,
    idempotency_key      text,
    request_fingerprint  text,
    engine_version       character varying(64),
    plan_id              text,
    node_id              text
);

CREATE TABLE IF NOT EXISTS public.agent_document_index (
    id              text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    asset_id        text NOT NULL UNIQUE,
    conversation_id text NOT NULL,
    original_name   text,
    mime_type       character varying(128),
    content_hash    character varying(64) NOT NULL,
    char_count      integer NOT NULL DEFAULT 0,
    summary         text,
    status          character varying(32) NOT NULL DEFAULT 'summarizing',
    parser_version  character varying(64) NOT NULL,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_messages_conv ON public.agent_messages (conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_conv ON public.agent_checkpoints (conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_token ON public.agent_checkpoints (form_token);
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv ON public.agent_assets (conversation_id, type);
CREATE INDEX IF NOT EXISTS idx_agent_document_index_conv_updated
    ON public.agent_document_index (conversation_id, updated_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 「/清空上下文」硬删除链路涉及的表（与 AIStoryboardBackend 的 Flyway 迁移对齐：
-- V39 agent_turns / agent_turn_events、V42 agent_canvas_sessions、V43 agent_canvas_runs、
-- V33 agent_external_actions、V30 agent_async_tasks、V36 agent_workflow_runs、
-- V25 agent_conversation_asset_groups、V41 时间线清单与评审记录）。
-- 这些表用于断言「真删除」确实把行删掉，而不是只改状态。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.agent_conversation_asset_groups (
    id             text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id text NOT NULL,
    group_id       text NOT NULL,
    snapshot_id    text,
    group_version  bigint,
    content_hash   text,
    target         text,
    created_at     timestamp with time zone DEFAULT now(),
    updated_at     timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_turns (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    status           character varying(32) NOT NULL DEFAULT 'running',
    runtime_engine   character varying(64) NOT NULL DEFAULT 'agentscope-java-2.0.4',
    input_text       text,
    last_sequence    integer NOT NULL DEFAULT 0,
    checkpoint_id    text,
    error            text,
    started_at       timestamp with time zone NOT NULL DEFAULT now(),
    completed_at     timestamp with time zone,
    client_request_id text
);

CREATE TABLE IF NOT EXISTS public.agent_turn_events (
    id           text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    turn_id      text NOT NULL REFERENCES public.agent_turns(id) ON DELETE CASCADE,
    sequence     integer NOT NULL,
    event_type   character varying(128) NOT NULL,
    payload      text NOT NULL DEFAULT '{}',
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    UNIQUE (turn_id, sequence)
);

CREATE TABLE IF NOT EXISTS public.agent_canvas_sessions (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    user_id          text,
    project_id       text,
    turn_id          text,
    runtime_reply_id text,
    runtime_tool_call_id text,
    canvas_id        text,
    canvas_title     text,
    intent           text,
    status           character varying(32) NOT NULL DEFAULT 'pending',
    choices_json     text,
    media_refs_json  text,
    error            text,
    expires_at       timestamp with time zone,
    ready_at         timestamp with time zone,
    resumed_at       timestamp with time zone,
    created_at       timestamp with time zone DEFAULT now(),
    updated_at       timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_canvas_runs (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    user_id          text,
    project_id       text,
    turn_id          text,
    tool_call_id     text,
    canvas_id        text,
    message_id       text,
    title            text,
    status           character varying(32) NOT NULL DEFAULT 'running',
    progress         integer,
    node_count       integer,
    edge_count       integer,
    revision         integer,
    plan_json        text,
    error            text,
    created_at       timestamp with time zone DEFAULT now(),
    updated_at       timestamp with time zone DEFAULT now(),
    completed_at     timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.agent_canvas_run_operations (
    id            text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    run_id        text NOT NULL REFERENCES public.agent_canvas_runs(id) ON DELETE CASCADE,
    operation_key text NOT NULL,
    result_json   text,
    created_at    timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_async_tasks (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    user_id          text,
    session_id       text,
    tool_call_id     text,
    tool_name        text,
    kind             text,
    status           character varying(32) NOT NULL DEFAULT 'queued',
    title            text,
    stage            text,
    progress         integer,
    request_json     text,
    result_json      text,
    result_summary   text,
    asset_ids_json   text,
    result_urls_json text,
    message_id       text,
    anchor_message_id text,
    parent_task_id   text,
    version          bigint,
    cancel_requested boolean,
    context_consumed_at timestamp with time zone,
    wakeup_status    text,
    wakeup_enqueued_at timestamp with time zone,
    wakeup_consumed_at timestamp with time zone,
    wakeup_message_id text,
    wakeup_error     text,
    wakeup_attempts  integer,
    error            text,
    idempotency_key  text,
    created_at       timestamp with time zone DEFAULT now(),
    updated_at       timestamp with time zone DEFAULT now(),
    completed_at     timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.agent_workflow_runs (
    id               text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    conversation_id  text NOT NULL,
    user_id          text,
    project_id       text,
    mode             text,
    status           character varying(32) NOT NULL DEFAULT 'running',
    original_prompt  text,
    constraint_json  text,
    version          bigint,
    created_at       timestamp with time zone DEFAULT now(),
    updated_at       timestamp with time zone DEFAULT now(),
    completed_at     timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.agent_external_actions (
    id              text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    action_key      text NOT NULL,
    conversation_id text,
    checkpoint_id   text,
    tool_call_id    text,
    tool_name       text,
    action_type     text,
    request_hash    text,
    status          character varying(32) NOT NULL DEFAULT 'pending',
    provider_task_id text,
    response_json   text,
    last_error      text,
    attempt         integer,
    created_at      timestamp with time zone DEFAULT now(),
    updated_at      timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_timeline_manifests (
    id                      text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    project_id              text,
    conversation_id         text,
    turn_id                 text,
    title                   text,
    status                  character varying(32) NOT NULL DEFAULT 'draft',
    total_duration_seconds  integer,
    manifest_json           text,
    created_at              timestamp with time zone DEFAULT now(),
    updated_at              timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_media_critiques (
    id                   text PRIMARY KEY DEFAULT (gen_random_uuid())::text,
    project_id           text,
    conversation_id      text,
    turn_id              text,
    item_id              text,
    media_type           text,
    asset_url            text,
    decision             text,
    hard_constraint_pass boolean,
    score_json           text,
    violations_json      text,
    reasons_json         text,
    repair_attempt       integer,
    created_at           timestamp with time zone DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_turns_conversation_started
    ON public.agent_turns (conversation_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_turn_events_replay
    ON public.agent_turn_events (turn_id, sequence ASC);
CREATE INDEX IF NOT EXISTS idx_agent_canvas_sessions_conv
    ON public.agent_canvas_sessions (conversation_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_canvas_runs_conv
    ON public.agent_canvas_runs (conversation_id, created_at DESC);
