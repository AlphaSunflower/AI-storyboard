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
