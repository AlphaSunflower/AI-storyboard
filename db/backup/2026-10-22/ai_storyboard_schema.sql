--
-- PostgreSQL database dump
--

\restrict N5gApLhPg0VyuBfk8GfgetenjTQgBRRfWCfFdgHOOCJg29qUp5hA0OERtf4hwq3

-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

-- 依赖扩展（gin_trgm_ops 索引需要）
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

--
-- Name: agentscope; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA agentscope;


--
-- Name: asset; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA asset;


--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agentscope_sessions; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.agentscope_sessions (
    session_id character varying(255) NOT NULL,
    state_key character varying(255) NOT NULL,
    item_index integer DEFAULT 0 NOT NULL,
    state_data text NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: library_ai_tag_suppressions_archive; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_ai_tag_suppressions_archive (
    asset_id text CONSTRAINT library_ai_tag_suppressions_asset_id_not_null NOT NULL,
    normalized_name text CONSTRAINT library_ai_tag_suppressions_normalized_name_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT library_ai_tag_suppressions_created_at_not_null NOT NULL
);


--
-- Name: library_analysis_tasks; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_analysis_tasks (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    asset_id text NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_asset_folders; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_asset_folders (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    scope character varying(16) NOT NULL,
    owner_project_id text,
    asset_id text NOT NULL,
    folder_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT library_asset_folders_scope_binding_check CHECK (((((scope)::text = 'PROJECT'::text) AND (owner_project_id IS NOT NULL)) OR (((scope)::text = 'GLOBAL'::text) AND (owner_project_id IS NULL)))),
    CONSTRAINT library_asset_folders_scope_check CHECK (((scope)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: library_asset_tags; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_asset_tags (
    asset_id text NOT NULL,
    tag_id text NOT NULL,
    source character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_assets; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_assets (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    scope character varying(16) NOT NULL,
    owner_project_id text,
    asset_type character varying(16) NOT NULL,
    ai_kind character varying(16),
    name text NOT NULL,
    description text,
    ai_description text,
    manual_override boolean DEFAULT false NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    source_type character varying(24),
    source_id text,
    cover_url text,
    search_text text DEFAULT ''::text NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    deleted_at timestamp with time zone,
    purge_after timestamp with time zone,
    purge_blocked_at timestamp with time zone,
    purged_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_conversation_id text,
    source_scene_id text,
    rating smallint DEFAULT 0 NOT NULL,
    flag character varying(8),
    color_label character varying(16),
    dominant_color character varying(7),
    CONSTRAINT library_assets_asset_type_check CHECK (((asset_type)::text = ANY (ARRAY[('IMAGE'::character varying)::text, ('VIDEO'::character varying)::text, ('AUDIO'::character varying)::text, ('DOCUMENT'::character varying)::text, ('OTHER'::character varying)::text, ('AI_ASSET'::character varying)::text, ('GROUP'::character varying)::text]))),
    CONSTRAINT library_assets_scope_check CHECK (((((scope)::text = 'PROJECT'::text) AND (owner_project_id IS NOT NULL)) OR (((scope)::text = 'GLOBAL'::text) AND (owner_project_id IS NULL)))),
    CONSTRAINT library_assets_scope_value_check CHECK (((scope)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: library_blobs; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_blobs (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    object_key text NOT NULL,
    sha256 character(64) NOT NULL,
    mime_type character varying(128) NOT NULL,
    size_bytes bigint NOT NULL,
    width integer,
    height integer,
    duration_seconds double precision,
    media_type character varying(16) NOT NULL,
    status character varying(24) DEFAULT 'READY'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT library_blobs_media_type_check CHECK (((media_type)::text = ANY (ARRAY[('IMAGE'::character varying)::text, ('VIDEO'::character varying)::text, ('AUDIO'::character varying)::text, ('DOCUMENT'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT library_blobs_size_bytes_check CHECK ((size_bytes >= 0))
);


--
-- Name: library_folders; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_folders (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    scope character varying(16) NOT NULL,
    owner_project_id text,
    parent_id text,
    name text NOT NULL,
    normalized_name text NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT library_folders_scope_binding_check CHECK (((((scope)::text = 'PROJECT'::text) AND (owner_project_id IS NOT NULL)) OR (((scope)::text = 'GLOBAL'::text) AND (owner_project_id IS NULL)))),
    CONSTRAINT library_folders_scope_check CHECK (((scope)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: library_group_items; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_group_items (
    group_id text NOT NULL,
    media_id text NOT NULL,
    member_asset_id text NOT NULL,
    sort_order integer NOT NULL,
    role character varying(24) DEFAULT 'REFERENCE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_group_outputs; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_group_outputs (
    group_id text NOT NULL,
    producer text NOT NULL,
    generation_record_id text NOT NULL,
    result_index integer NOT NULL,
    result_asset_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_group_snapshot_items; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_group_snapshot_items (
    snapshot_id text NOT NULL,
    asset_id text NOT NULL,
    media_id text NOT NULL,
    blob_id text NOT NULL,
    object_key text NOT NULL,
    media_type character varying(16) NOT NULL,
    role character varying(24) NOT NULL,
    sort_order integer NOT NULL
);


--
-- Name: library_group_snapshots; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_group_snapshots (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    group_id text NOT NULL,
    group_version bigint NOT NULL,
    content_hash character(64) NOT NULL,
    target character varying(16) DEFAULT 'VIDEO'::character varying NOT NULL,
    created_by_user_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_groups; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_groups (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    asset_id text NOT NULL,
    owner_user_id text NOT NULL,
    scope character varying(16) NOT NULL,
    owner_project_id text,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT library_groups_scope_check CHECK (((scope)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: library_idempotency_keys; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_idempotency_keys (
    owner_user_id text NOT NULL,
    operation character varying(64) NOT NULL,
    idempotency_key text NOT NULL,
    resource_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL
);


--
-- Name: library_import_items; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_import_items (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    job_id text NOT NULL,
    item_key text NOT NULL,
    relative_path text NOT NULL,
    file_name text NOT NULL,
    size_bytes bigint NOT NULL,
    mime_type character varying(128),
    sha256 character(64),
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    asset_id text,
    error_code character varying(64),
    error_message text,
    attempt_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL
);


--
-- Name: library_import_jobs; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_import_jobs (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    project_id text,
    scope character varying(16) NOT NULL,
    manifest_hash character(64) NOT NULL,
    confirmation_hash character(64) NOT NULL,
    confirmation_expires_at timestamp with time zone NOT NULL,
    status character varying(24) DEFAULT 'CREATED'::character varying NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    total_bytes bigint DEFAULT 0 NOT NULL,
    ignored_count integer DEFAULT 0 NOT NULL,
    processed_count integer DEFAULT 0 NOT NULL,
    succeeded_count integer DEFAULT 0 NOT NULL,
    duplicate_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    mode character varying(16) DEFAULT 'ASSETS'::character varying NOT NULL,
    group_id text
);


--
-- Name: library_media; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_media (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    asset_id text NOT NULL,
    blob_id text NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    role character varying(24) DEFAULT 'REFERENCE'::character varying NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    constraint_text text,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_project_links; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_project_links (
    asset_id text NOT NULL,
    project_id text NOT NULL,
    added_by_user_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_saved_views; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_saved_views (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    scope character varying(16) NOT NULL,
    owner_project_id text,
    name text NOT NULL,
    normalized_name text NOT NULL,
    filters jsonb DEFAULT '{}'::jsonb NOT NULL,
    sort jsonb DEFAULT '{}'::jsonb NOT NULL,
    view_mode character varying(16) DEFAULT 'folders'::character varying NOT NULL,
    pinned boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT library_saved_views_scope_binding_check CHECK (((((scope)::text = 'PROJECT'::text) AND (owner_project_id IS NOT NULL)) OR (((scope)::text = 'GLOBAL'::text) AND (owner_project_id IS NULL)))),
    CONSTRAINT library_saved_views_scope_check CHECK (((scope)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: library_storage_cleanup_tasks; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_storage_cleanup_tasks (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    object_key text NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_tags; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_tags (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    name text NOT NULL,
    normalized_name text NOT NULL,
    color character varying(16),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: library_upload_sessions; Type: TABLE; Schema: asset; Owner: -
--

CREATE TABLE asset.library_upload_sessions (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    owner_user_id text NOT NULL,
    project_id text,
    file_name text NOT NULL,
    relative_path text,
    mime_type character varying(128) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character(64) NOT NULL,
    object_key text NOT NULL,
    minio_upload_id text NOT NULL,
    part_size_bytes bigint NOT NULL,
    status character varying(24) DEFAULT 'CREATED'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_archive_cleanups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_archive_cleanups (
    conversation_id text NOT NULL,
    user_id text,
    deleted_rows integer DEFAULT 0 NOT NULL,
    detail text,
    cleaned_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE agent_archive_cleanups; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.agent_archive_cleanups IS '已归档会话清理台账：每个会话只清一次，行随 conversations 级联删除';


--
-- Name: agent_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_assets (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text,
    type text NOT NULL,
    url text,
    prompt text,
    model text,
    status text DEFAULT 'queued'::text NOT NULL,
    task_id text,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    gen_type character varying(32),
    params text,
    reference_urls text,
    result_urls text,
    duration_ms bigint,
    scene_id character varying(64),
    purpose text,
    kind character varying(16) DEFAULT 'reference'::character varying NOT NULL,
    user_id text,
    project_id text,
    loop_run_id text,
    chain_run_id text,
    idempotency_key text,
    request_fingerprint text,
    engine_version character varying(64),
    plan_id text,
    node_id text,
    original_name text,
    mime_type character varying(128),
    description text
);


--
-- Name: agent_async_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_async_tasks (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    user_id text NOT NULL,
    session_id text,
    tool_call_id text,
    tool_name text NOT NULL,
    kind character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'queued'::character varying NOT NULL,
    request_json text,
    result_json text,
    asset_ids_json text,
    result_urls_json text,
    message_id text,
    error text,
    idempotency_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    title text,
    stage character varying(64),
    progress integer,
    result_summary text,
    anchor_message_id text,
    parent_task_id text,
    version bigint DEFAULT 0 NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    context_consumed_at timestamp with time zone,
    wakeup_status character varying(32) DEFAULT 'not_required'::character varying NOT NULL,
    wakeup_enqueued_at timestamp with time zone,
    wakeup_consumed_at timestamp with time zone,
    wakeup_message_id text,
    wakeup_error text,
    wakeup_attempts integer DEFAULT 0 NOT NULL
);


--
-- Name: agent_canvas_run_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_canvas_run_operations (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    run_id text NOT NULL,
    operation_key text NOT NULL,
    result_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_canvas_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_canvas_runs (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    user_id text NOT NULL,
    project_id text NOT NULL,
    turn_id text NOT NULL,
    tool_call_id text NOT NULL,
    canvas_id text NOT NULL,
    message_id text,
    title text NOT NULL,
    status character varying(32) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    node_count integer DEFAULT 0 NOT NULL,
    edge_count integer DEFAULT 0 NOT NULL,
    revision integer DEFAULT 0 NOT NULL,
    plan_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone
);


--
-- Name: agent_canvas_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_canvas_sessions (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    user_id text NOT NULL,
    project_id text NOT NULL,
    turn_id text NOT NULL,
    runtime_reply_id text NOT NULL,
    runtime_tool_call_id text NOT NULL,
    canvas_id text,
    canvas_title text DEFAULT ''::text NOT NULL,
    intent text DEFAULT ''::text NOT NULL,
    status character varying(32) DEFAULT 'pending_selection'::character varying NOT NULL,
    choices_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    media_refs_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    error text,
    expires_at timestamp with time zone NOT NULL,
    ready_at timestamp with time zone,
    resumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_checkpoints; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_checkpoints (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    action text NOT NULL,
    form_token text NOT NULL,
    plan text,
    step text,
    status text DEFAULT 'pending'::text NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    owner_engine character varying(32) DEFAULT 'LEGACY'::character varying NOT NULL,
    state_schema_version integer DEFAULT 1 NOT NULL,
    plan_id text,
    node_id text,
    runtime_session_id text,
    runtime_reply_id text,
    runtime_tool_call_id text,
    runtime_tool_name text,
    resume_payload text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    runtime_tool_calls_json text
);


--
-- Name: agent_conversation_asset_groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_conversation_asset_groups (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    group_id text NOT NULL,
    snapshot_id text NOT NULL,
    group_version bigint DEFAULT 0 NOT NULL,
    content_hash character(64) DEFAULT ''::bpchar NOT NULL,
    target character varying(16) DEFAULT 'AGENT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_document_index; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_document_index (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    asset_id text NOT NULL,
    conversation_id text NOT NULL,
    original_name text,
    mime_type character varying(128),
    content_hash character varying(64) NOT NULL,
    char_count integer DEFAULT 0 NOT NULL,
    summary text,
    status character varying(32) DEFAULT 'summarizing'::character varying NOT NULL,
    parser_version character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_external_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_external_actions (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    action_key text NOT NULL,
    conversation_id text NOT NULL,
    checkpoint_id text,
    tool_call_id text,
    tool_name text NOT NULL,
    action_type text NOT NULL,
    request_hash text,
    status character varying(32) NOT NULL,
    provider_task_id text,
    response_json text,
    last_error text,
    attempt integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_global_memories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_global_memories (
    user_id character varying(64) NOT NULL,
    content text NOT NULL,
    content_hash character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_handoffs_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_handoffs_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_handoffs_id_not_null NOT NULL,
    loop_run_id text CONSTRAINT agent_handoffs_loop_run_id_not_null NOT NULL,
    from_chain character varying(32) CONSTRAINT agent_handoffs_from_chain_not_null NOT NULL,
    to_chain character varying(32) CONSTRAINT agent_handoffs_to_chain_not_null NOT NULL,
    reason text,
    state text DEFAULT '{}'::text CONSTRAINT agent_handoffs_state_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agent_handoffs_created_at_not_null NOT NULL
);


--
-- Name: agent_loop_runs_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_loop_runs_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_loop_runs_id_not_null NOT NULL,
    conversation_id text CONSTRAINT agent_loop_runs_conversation_id_not_null NOT NULL,
    turn_id text,
    trace_id text CONSTRAINT agent_loop_runs_trace_id_not_null NOT NULL,
    engine_version character varying(64) CONSTRAINT agent_loop_runs_engine_version_not_null NOT NULL,
    current_chain character varying(32),
    status character varying(32) DEFAULT 'running'::character varying CONSTRAINT agent_loop_runs_status_not_null NOT NULL,
    decision character varying(32),
    state text DEFAULT '{}'::text CONSTRAINT agent_loop_runs_state_not_null NOT NULL,
    checkpoint_id text,
    error text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agent_loop_runs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT agent_loop_runs_updated_at_not_null NOT NULL
);


--
-- Name: agent_loop_steps_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_loop_steps_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_loop_steps_id_not_null NOT NULL,
    loop_run_id text CONSTRAINT agent_loop_steps_loop_run_id_not_null NOT NULL,
    step_index integer CONSTRAINT agent_loop_steps_step_index_not_null NOT NULL,
    chain character varying(32),
    phase character varying(64),
    status character varying(32) CONSTRAINT agent_loop_steps_status_not_null NOT NULL,
    decision character varying(32),
    input_summary text,
    output_summary text,
    tool_call_id text,
    job_id text,
    error text,
    started_at timestamp with time zone DEFAULT now() CONSTRAINT agent_loop_steps_started_at_not_null NOT NULL,
    completed_at timestamp with time zone,
    plan_id text,
    node_id text,
    attempt_no integer
);


--
-- Name: agent_media_critiques; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_media_critiques (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    project_id text,
    conversation_id text,
    turn_id text,
    item_id text DEFAULT ''::text NOT NULL,
    media_type character varying(32) DEFAULT 'image'::character varying NOT NULL,
    asset_url text DEFAULT ''::text NOT NULL,
    decision character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    hard_constraint_pass boolean DEFAULT true NOT NULL,
    score_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    violations_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    reasons_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    repair_attempt integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_memories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_memories (
    id character varying(64) NOT NULL,
    user_id character varying(64) NOT NULL,
    project_id character varying(64) NOT NULL,
    conversation_id character varying(64),
    kind character varying(32) NOT NULL,
    content text NOT NULL,
    content_hash character varying(64) NOT NULL,
    source character varying(32) DEFAULT 'agent'::character varying NOT NULL,
    confidence double precision DEFAULT 0.8 NOT NULL,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_memory_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_memory_settings (
    user_id character varying(64) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_messages (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    role text NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: agent_packages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_packages (
    id character varying(64) NOT NULL,
    kind character varying(16) NOT NULL,
    name character varying(128) NOT NULL,
    version character varying(32) NOT NULL,
    manifest_json text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_recovery_leases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_recovery_leases (
    resource_type character varying(64) NOT NULL,
    resource_id character varying(255) NOT NULL,
    lease_owner character varying(128) NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_sandbox_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_sandbox_sessions (
    conversation_id text NOT NULL,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    last_used_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_subagent_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_subagent_runs (
    id character varying(180) NOT NULL,
    graph_id character varying(64) NOT NULL,
    conversation_id character varying(64),
    turn_id character varying(64),
    tool_call_id character varying(64),
    node_id character varying(64) NOT NULL,
    role character varying(64),
    status character varying(32) NOT NULL,
    instruction text,
    output text,
    error text,
    tokens integer DEFAULT 0,
    duration_ms bigint DEFAULT 0,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    task_json text,
    graph_json text,
    context_ref character varying(512)
);


--
-- Name: agent_task_edges_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_task_edges_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_task_edges_id_not_null NOT NULL,
    plan_id text CONSTRAINT agent_task_edges_plan_id_not_null NOT NULL,
    from_node_id text CONSTRAINT agent_task_edges_from_node_id_not_null NOT NULL,
    to_node_id text CONSTRAINT agent_task_edges_to_node_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agent_task_edges_created_at_not_null NOT NULL
);


--
-- Name: agent_task_nodes_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_task_nodes_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_task_nodes_id_not_null NOT NULL,
    plan_id text CONSTRAINT agent_task_nodes_plan_id_not_null NOT NULL,
    node_id text CONSTRAINT agent_task_nodes_node_id_not_null NOT NULL,
    capability_key text CONSTRAINT agent_task_nodes_capability_key_not_null NOT NULL,
    title text,
    state character varying(32) CONSTRAINT agent_task_nodes_state_not_null NOT NULL,
    attempt_count integer DEFAULT 0 CONSTRAINT agent_task_nodes_attempt_count_not_null NOT NULL,
    hitl_policy character varying(32) DEFAULT 'NONE'::character varying CONSTRAINT agent_task_nodes_hitl_policy_not_null NOT NULL,
    evaluation_mode character varying(32) DEFAULT 'DETERMINISTIC'::character varying CONSTRAINT agent_task_nodes_evaluation_mode_not_null NOT NULL,
    quality_criteria text,
    output_summary text,
    async_task_id text,
    error text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agent_task_nodes_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT agent_task_nodes_updated_at_not_null NOT NULL
);


--
-- Name: agent_task_plans_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_task_plans_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT agent_task_plans_id_not_null NOT NULL,
    conversation_id text CONSTRAINT agent_task_plans_conversation_id_not_null NOT NULL,
    goal text CONSTRAINT agent_task_plans_goal_not_null NOT NULL,
    status character varying(32) CONSTRAINT agent_task_plans_status_not_null NOT NULL,
    version integer DEFAULT 1 CONSTRAINT agent_task_plans_version_not_null NOT NULL,
    plan_json text DEFAULT '{}'::text CONSTRAINT agent_task_plans_plan_json_not_null NOT NULL,
    plan_signature text,
    max_nodes integer DEFAULT 20 CONSTRAINT agent_task_plans_max_nodes_not_null NOT NULL,
    max_attempts_per_node integer DEFAULT 3 CONSTRAINT agent_task_plans_max_attempts_per_node_not_null NOT NULL,
    max_replans integer DEFAULT 2 CONSTRAINT agent_task_plans_max_replans_not_null NOT NULL,
    replan_count integer DEFAULT 0 CONSTRAINT agent_task_plans_replan_count_not_null NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agent_task_plans_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT agent_task_plans_updated_at_not_null NOT NULL
);


--
-- Name: agent_timeline_manifests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_timeline_manifests (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    project_id text,
    conversation_id text,
    turn_id text,
    title text DEFAULT ''::text NOT NULL,
    status character varying(32) DEFAULT 'draft'::character varying NOT NULL,
    total_duration_seconds integer DEFAULT 0 NOT NULL,
    manifest_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_tool_invocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_tool_invocations (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    invocation_key text NOT NULL,
    trace_id text,
    loop_run_id text,
    loop_step_id text,
    task_id text,
    conversation_id text,
    user_id text,
    project_id text,
    tool_call_id text,
    tool_name text NOT NULL,
    tool_kind character varying(32),
    source character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    input_hash text,
    input_summary text,
    output_hash text,
    output_summary text,
    error_code text,
    error_message text,
    external_action_key text,
    provider_task_id text,
    result_asset_ids text,
    expected_reference_count integer,
    resolved_reference_count integer,
    applied_reference_count integer,
    skill_version text,
    model text,
    engine_version text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    duration_ms bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_turn_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_turn_events (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    turn_id text NOT NULL,
    sequence integer NOT NULL,
    event_type character varying(128) NOT NULL,
    payload text DEFAULT '{}'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_turns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_turns (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    status character varying(32) DEFAULT 'running'::character varying NOT NULL,
    runtime_engine character varying(64) DEFAULT 'agentscope-java-2.0.3'::character varying NOT NULL,
    input_text text,
    last_sequence integer DEFAULT 0 NOT NULL,
    checkpoint_id text,
    error text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    client_request_id character varying(128)
);


--
-- Name: agent_workflow_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_workflow_runs (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    conversation_id text NOT NULL,
    user_id text NOT NULL,
    project_id text,
    mode character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'pending'::character varying NOT NULL,
    original_prompt text NOT NULL,
    constraint_json text DEFAULT '{}'::text NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone
);


--
-- Name: agent_workflow_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_workflow_steps (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    workflow_run_id text NOT NULL,
    step_index integer NOT NULL,
    step_key character varying(128) NOT NULL,
    title text NOT NULL,
    tool_name character varying(128) NOT NULL,
    status character varying(32) DEFAULT 'pending'::character varying NOT NULL,
    depends_on_json text DEFAULT '[]'::text NOT NULL,
    input_json text DEFAULT '{}'::text NOT NULL,
    output_json text DEFAULT '{}'::text NOT NULL,
    marker text DEFAULT ''::text NOT NULL,
    error text,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone
);


--
-- Name: agentscope_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agentscope_sessions (
    session_id character varying(255) NOT NULL,
    state_key character varying(255) NOT NULL,
    item_index integer DEFAULT 0 NOT NULL,
    state_data text NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: agentscope_store; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agentscope_store (
    namespace_path character varying(2048) NOT NULL,
    item_key character varying(255) NOT NULL,
    value_json text NOT NULL,
    version bigint NOT NULL,
    updated_at bigint NOT NULL
);


--
-- Name: asset_images_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_images_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT asset_images_id_not_null NOT NULL,
    asset_id text CONSTRAINT asset_images_asset_id_not_null NOT NULL,
    url text CONSTRAINT asset_images_url_not_null NOT NULL,
    sort_order integer DEFAULT 0 CONSTRAINT asset_images_sort_order_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT asset_images_created_at_not_null NOT NULL,
    file_name character varying(255),
    text text
);


--
-- Name: asset_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_outbox (
    event_id text NOT NULL,
    producer character varying(32) NOT NULL,
    generation_record_id text NOT NULL,
    result_index integer NOT NULL,
    group_id text,
    result_asset_id text,
    payload_json text NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: assets_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assets_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT assets_id_not_null NOT NULL,
    user_id text CONSTRAINT assets_user_id_not_null NOT NULL,
    project_id text,
    type text CONSTRAINT assets_type_not_null NOT NULL,
    name text CONSTRAINT assets_name_not_null NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT assets_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT assets_updated_at_not_null NOT NULL
);


--
-- Name: canvas_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_assets (
    id text NOT NULL,
    canvas_id text NOT NULL,
    project_id text,
    user_id text NOT NULL,
    type character varying(16) NOT NULL,
    mime_type character varying(128) NOT NULL,
    object_url text NOT NULL,
    object_key text NOT NULL,
    generation_record_id text,
    producer_node_id text,
    status character varying(24) DEFAULT 'ready'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    library_asset_id text,
    library_sync_state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    CONSTRAINT canvas_assets_status_check CHECK (((status)::text = ANY (ARRAY[('pending'::character varying)::text, ('ready'::character varying)::text, ('failed'::character varying)::text]))),
    CONSTRAINT canvas_assets_type_check CHECK (((type)::text = ANY (ARRAY[('image'::character varying)::text, ('video'::character varying)::text, ('audio'::character varying)::text])))
);


--
-- Name: canvas_execution_nodes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_execution_nodes (
    id text NOT NULL,
    execution_id text NOT NULL,
    "position" integer NOT NULL,
    node_id text NOT NULL,
    action_id text NOT NULL,
    capability_id text NOT NULL,
    client_request_id text NOT NULL,
    params text,
    source_asset_ids text,
    result_slot_node_id text,
    status text NOT NULL,
    job_id text,
    result_asset_ids text,
    error text,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    attached_asset_id text
);


--
-- Name: canvas_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_executions (
    id text NOT NULL,
    canvas_id text NOT NULL,
    user_id text NOT NULL,
    status text NOT NULL,
    max_parallel integer DEFAULT 1 NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: canvas_graph_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_graph_operations (
    operation_key text NOT NULL,
    canvas_id text NOT NULL,
    user_id text NOT NULL,
    project_id text NOT NULL,
    run_id text,
    command_name text NOT NULL,
    status character varying(24) DEFAULT 'running'::character varying NOT NULL,
    result_json text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT canvas_graph_operations_status_check CHECK (((status)::text = ANY (ARRAY[('running'::character varying)::text, ('completed'::character varying)::text, ('failed'::character varying)::text])))
);


--
-- Name: canvas_job_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_job_events (
    job_id text NOT NULL,
    seq bigint NOT NULL,
    type character varying(64) NOT NULL,
    payload text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: canvas_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canvas_jobs (
    id text NOT NULL,
    client_request_id text NOT NULL,
    user_id text NOT NULL,
    conversation_id text,
    project_id character varying(64),
    capability_id character varying(64) NOT NULL,
    node_ids text,
    selected_node_id character varying(64),
    source_asset_ids text,
    params text,
    status character varying(16) DEFAULT 'queued'::character varying NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    error text,
    result_asset_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    cancel_requested_at timestamp with time zone,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    heartbeat_at timestamp with time zone,
    lease_owner text,
    lease_expires_at timestamp with time zone,
    state_version bigint DEFAULT 0 NOT NULL,
    last_event_seq bigint DEFAULT 0 NOT NULL,
    result_asset_ids text,
    provider_task_id text,
    batch_id text
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    user_id text NOT NULL,
    project_id text NOT NULL,
    title text DEFAULT '新对话'::text NOT NULL,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    canvas_context text,
    orchestration_mode character varying(32) DEFAULT 'LEGACY'::character varying NOT NULL,
    engine_version character varying(64) DEFAULT 'plan-graph-v1'::character varying NOT NULL,
    debug_comparison_enabled boolean DEFAULT true NOT NULL,
    approval_mode character varying(32) DEFAULT 'PLAN_APPROVAL'::character varying NOT NULL,
    agentscope_session_initialized_at timestamp with time zone
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: morrow_canvas_graphs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.morrow_canvas_graphs (
    canvas_id text NOT NULL,
    graph_json text NOT NULL,
    revision integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: morrow_canvases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.morrow_canvases (
    id text NOT NULL,
    project_id text NOT NULL,
    title text DEFAULT '画布'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: project_creative_contexts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_creative_contexts (
    project_id text NOT NULL,
    user_id text NOT NULL,
    objective text DEFAULT ''::text NOT NULL,
    creative_direction text DEFAULT ''::text NOT NULL,
    visual_style text DEFAULT ''::text NOT NULL,
    references_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    hard_constraints_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    soft_constraints_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    approved_assets_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    rejected_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    feedback_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    model_preferences_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    user_id text NOT NULL,
    name text DEFAULT '未命名项目'::text NOT NULL,
    description text,
    creation_type text DEFAULT 'movie'::text NOT NULL,
    custom_type_desc text,
    aspect_ratio text DEFAULT '16:9'::text NOT NULL,
    reference_image_url text,
    script_text text,
    ai_model text DEFAULT 'gemini-3-flash-preview'::text NOT NULL,
    status text DEFAULT 'draft'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: scene_asset_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scene_asset_links (
    scene_id text NOT NULL,
    asset_id text NOT NULL,
    purpose character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: scene_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scene_assets (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    scene_id text NOT NULL,
    asset_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    purpose character varying(16) DEFAULT 'image'::character varying NOT NULL
);


--
-- Name: scene_material_group_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scene_material_group_snapshots (
    scene_id text NOT NULL,
    snapshot_id text NOT NULL,
    group_id text NOT NULL,
    group_version bigint NOT NULL,
    content_hash character(64) NOT NULL,
    snapshot_json text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    purpose character varying(16) DEFAULT 'video'::character varying NOT NULL
);


--
-- Name: scene_prompts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scene_prompts (
    scene_id text NOT NULL,
    script_content text,
    image_prompt text,
    video_prompt text,
    negative_prompt text,
    camera_movement text,
    shot_type text,
    sound_design text,
    ai_model text,
    video_resolution text,
    duration integer,
    image_model character varying(128),
    image_size character varying(64),
    image_quality character varying(32),
    image_n integer,
    video_model character varying(128),
    video_aspect_ratio character varying(32),
    continuity_note text
);


--
-- Name: scene_reference_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scene_reference_images (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    scene_id text NOT NULL,
    image_url text NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    type character varying(16) DEFAULT 'image'::character varying NOT NULL,
    file_name character varying(255),
    file_size bigint,
    purpose character varying(16) DEFAULT 'image'::character varying NOT NULL,
    role character varying(32) DEFAULT 'REFERENCE'::character varying NOT NULL,
    source_type character varying(24) DEFAULT 'upload'::character varying NOT NULL,
    source_id character varying(64),
    source_conversation_id character varying(64),
    media_id text
);


--
-- Name: scenes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scenes (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    project_id text NOT NULL,
    scene_number integer NOT NULL,
    image_url text,
    video_url text,
    image_status text DEFAULT 'pending'::text NOT NULL,
    video_status text DEFAULT 'pending'::text NOT NULL,
    image_task_id text,
    video_task_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    image_urls text,
    conversation_id text
);


--
-- Name: scenes_backup_pre_v15_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scenes_backup_pre_v15_archive (
    id text,
    project_id text,
    scene_number integer,
    script_content text,
    image_prompt text,
    video_prompt text,
    negative_prompt text,
    camera_movement text,
    shot_type text,
    sound_design text,
    ai_model text,
    video_resolution text,
    duration integer,
    image_url text,
    video_url text,
    image_status text,
    video_status text,
    image_task_id text,
    video_task_id text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    image_urls text,
    image_model character varying(128),
    image_size character varying(64),
    image_quality character varying(32),
    image_n integer,
    video_model character varying(128),
    video_aspect_ratio character varying(32)
);


--
-- Name: storage_cleanup_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storage_cleanup_queue (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    object_url text NOT NULL,
    status character varying(24) DEFAULT 'pending'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: storyboard_image_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storyboard_image_task (
    id character varying(64) NOT NULL,
    scene_id character varying(64) NOT NULL,
    project_id character varying(64),
    conversation_id character varying(64),
    status character varying(16) DEFAULT 'queued'::character varying NOT NULL,
    prompt text,
    model character varying(64),
    size character varying(32),
    quality character varying(32),
    n integer DEFAULT 1 NOT NULL,
    mode character varying(16),
    source_image_url text,
    ref_image_urls text,
    task_id character varying(64),
    progress integer DEFAULT 0 NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone
);


--
-- Name: storyboard_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storyboard_runs (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    user_id text NOT NULL,
    project_id text,
    conversation_id text,
    canvas_id text,
    original_script text NOT NULL,
    selected_script text,
    expansion_script text,
    stage character varying(32) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    plot_assessment text DEFAULT '{}'::text NOT NULL,
    story_bible text DEFAULT '{}'::text NOT NULL,
    roles text DEFAULT '[]'::text NOT NULL,
    assets text DEFAULT '[]'::text NOT NULL,
    pseudo_shots text DEFAULT '[]'::text NOT NULL,
    final_shots text DEFAULT '[]'::text NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    requested_shot_count text DEFAULT ''::text NOT NULL
);


--
-- Name: storyboard_shot_changes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storyboard_shot_changes (
    id character varying(64) NOT NULL,
    run_id character varying(64) NOT NULL,
    shot_id character varying(64) NOT NULL,
    shot_ordinal integer DEFAULT 0 NOT NULL,
    kind character varying(16) NOT NULL,
    source character varying(16) NOT NULL,
    field character varying(32) NOT NULL,
    before_value text,
    after_value text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: storyboard_shot_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storyboard_shot_records (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    run_id text NOT NULL,
    phase character varying(16) NOT NULL,
    segment_id character varying(128) NOT NULL,
    shot_id character varying(128) NOT NULL,
    shot_ordinal integer NOT NULL,
    status character varying(32) NOT NULL,
    payload text DEFAULT '{}'::text NOT NULL,
    reference_asset_ids text DEFAULT '[]'::text NOT NULL,
    provider_task_id text,
    error text,
    attempt integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    project_scene_id text
);


--
-- Name: storyboard_video_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storyboard_video_queue (
    id character varying(64) NOT NULL,
    scene_id character varying(64) NOT NULL,
    project_id character varying(64),
    conversation_id character varying(64),
    status character varying(16) DEFAULT 'queued'::character varying NOT NULL,
    prompt text,
    model character varying(64),
    resolution character varying(32),
    size character varying(32),
    aspect_ratio character varying(16),
    duration integer,
    negative_prompt text,
    source_image_url text,
    ref_image_urls text,
    ref_asset_ids text,
    task_id character varying(64),
    progress integer DEFAULT 0 NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone
);


--
-- Name: user_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_feedback (
    id character varying(36) NOT NULL,
    user_id character varying(64) NOT NULL,
    content text NOT NULL,
    image_urls text,
    status character varying(16) DEFAULT 'open'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: user_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notifications (
    id character varying(64) NOT NULL,
    user_id character varying(64) NOT NULL,
    type character varying(32) NOT NULL,
    title character varying(200) NOT NULL,
    body text,
    link_type character varying(32),
    link_id character varying(64),
    dedupe_key character varying(200),
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: user_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_settings (
    user_id character varying(64) NOT NULL,
    default_approval_mode character varying(32) DEFAULT 'PLAN_APPROVAL'::character varying NOT NULL,
    busy_send_behavior character varying(16) DEFAULT 'steer'::character varying NOT NULL,
    work_step_display character varying(16) DEFAULT 'detailed'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    theme_preference character varying(16) DEFAULT 'light'::character varying NOT NULL,
    CONSTRAINT ck_user_settings_theme_preference CHECK (((theme_preference)::text = ANY (ARRAY[('light'::character varying)::text, ('dark'::character varying)::text, ('system'::character varying)::text])))
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id text DEFAULT (gen_random_uuid())::text NOT NULL,
    email text NOT NULL,
    password_hash text NOT NULL,
    display_name text NOT NULL,
    role text DEFAULT 'member'::text NOT NULL,
    status text DEFAULT 'enabled'::text NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_users_role CHECK ((role = ANY (ARRAY['member'::text, 'admin'::text]))),
    CONSTRAINT chk_users_status CHECK ((status = ANY (ARRAY['enabled'::text, 'disabled'::text])))
);


--
-- Name: agentscope_sessions agentscope_sessions_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.agentscope_sessions
    ADD CONSTRAINT agentscope_sessions_pkey PRIMARY KEY (session_id, state_key, item_index);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: library_ai_tag_suppressions_archive library_ai_tag_suppressions_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_ai_tag_suppressions_archive
    ADD CONSTRAINT library_ai_tag_suppressions_pkey PRIMARY KEY (asset_id, normalized_name);


--
-- Name: library_analysis_tasks library_analysis_tasks_asset_id_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_analysis_tasks
    ADD CONSTRAINT library_analysis_tasks_asset_id_key UNIQUE (asset_id);


--
-- Name: library_analysis_tasks library_analysis_tasks_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_analysis_tasks
    ADD CONSTRAINT library_analysis_tasks_pkey PRIMARY KEY (id);


--
-- Name: library_asset_folders library_asset_folders_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_folders
    ADD CONSTRAINT library_asset_folders_pkey PRIMARY KEY (id);


--
-- Name: library_asset_tags library_asset_tags_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_tags
    ADD CONSTRAINT library_asset_tags_pkey PRIMARY KEY (asset_id, tag_id);


--
-- Name: library_assets library_assets_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_assets
    ADD CONSTRAINT library_assets_pkey PRIMARY KEY (id);


--
-- Name: library_blobs library_blobs_object_key_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_blobs
    ADD CONSTRAINT library_blobs_object_key_key UNIQUE (object_key);


--
-- Name: library_blobs library_blobs_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_blobs
    ADD CONSTRAINT library_blobs_pkey PRIMARY KEY (id);


--
-- Name: library_blobs library_blobs_sha256_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_blobs
    ADD CONSTRAINT library_blobs_sha256_key UNIQUE (sha256);


--
-- Name: library_folders library_folders_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_folders
    ADD CONSTRAINT library_folders_pkey PRIMARY KEY (id);


--
-- Name: library_group_items library_group_items_group_id_sort_order_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_items
    ADD CONSTRAINT library_group_items_group_id_sort_order_key UNIQUE (group_id, sort_order);


--
-- Name: library_group_items library_group_items_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_items
    ADD CONSTRAINT library_group_items_pkey PRIMARY KEY (group_id, media_id);


--
-- Name: library_group_outputs library_group_outputs_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_outputs
    ADD CONSTRAINT library_group_outputs_pkey PRIMARY KEY (producer, generation_record_id, result_index);


--
-- Name: library_group_snapshot_items library_group_snapshot_items_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshot_items
    ADD CONSTRAINT library_group_snapshot_items_pkey PRIMARY KEY (snapshot_id, media_id);


--
-- Name: library_group_snapshot_items library_group_snapshot_items_snapshot_id_sort_order_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshot_items
    ADD CONSTRAINT library_group_snapshot_items_snapshot_id_sort_order_key UNIQUE (snapshot_id, sort_order);


--
-- Name: library_group_snapshots library_group_snapshots_group_id_group_version_content_hash_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshots
    ADD CONSTRAINT library_group_snapshots_group_id_group_version_content_hash_key UNIQUE (group_id, group_version, content_hash, target);


--
-- Name: library_group_snapshots library_group_snapshots_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshots
    ADD CONSTRAINT library_group_snapshots_pkey PRIMARY KEY (id);


--
-- Name: library_groups library_groups_asset_id_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_groups
    ADD CONSTRAINT library_groups_asset_id_key UNIQUE (asset_id);


--
-- Name: library_groups library_groups_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_groups
    ADD CONSTRAINT library_groups_pkey PRIMARY KEY (id);


--
-- Name: library_idempotency_keys library_idempotency_keys_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_idempotency_keys
    ADD CONSTRAINT library_idempotency_keys_pkey PRIMARY KEY (owner_user_id, operation, idempotency_key);


--
-- Name: library_import_items library_import_items_job_id_item_key_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_items
    ADD CONSTRAINT library_import_items_job_id_item_key_key UNIQUE (job_id, item_key);


--
-- Name: library_import_items library_import_items_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_items
    ADD CONSTRAINT library_import_items_pkey PRIMARY KEY (id);


--
-- Name: library_import_jobs library_import_jobs_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_jobs
    ADD CONSTRAINT library_import_jobs_pkey PRIMARY KEY (id);


--
-- Name: library_media library_media_asset_id_blob_id_sort_order_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_media
    ADD CONSTRAINT library_media_asset_id_blob_id_sort_order_key UNIQUE (asset_id, blob_id, sort_order);


--
-- Name: library_media library_media_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_media
    ADD CONSTRAINT library_media_pkey PRIMARY KEY (id);


--
-- Name: library_project_links library_project_links_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_project_links
    ADD CONSTRAINT library_project_links_pkey PRIMARY KEY (asset_id, project_id);


--
-- Name: library_saved_views library_saved_views_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_saved_views
    ADD CONSTRAINT library_saved_views_pkey PRIMARY KEY (id);


--
-- Name: library_storage_cleanup_tasks library_storage_cleanup_tasks_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_storage_cleanup_tasks
    ADD CONSTRAINT library_storage_cleanup_tasks_pkey PRIMARY KEY (id);


--
-- Name: library_tags library_tags_owner_user_id_normalized_name_key; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_tags
    ADD CONSTRAINT library_tags_owner_user_id_normalized_name_key UNIQUE (owner_user_id, normalized_name);


--
-- Name: library_tags library_tags_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_tags
    ADD CONSTRAINT library_tags_pkey PRIMARY KEY (id);


--
-- Name: library_upload_sessions library_upload_sessions_pkey; Type: CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_upload_sessions
    ADD CONSTRAINT library_upload_sessions_pkey PRIMARY KEY (id);


--
-- Name: agent_archive_cleanups agent_archive_cleanups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_archive_cleanups
    ADD CONSTRAINT agent_archive_cleanups_pkey PRIMARY KEY (conversation_id);


--
-- Name: agent_assets agent_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_assets
    ADD CONSTRAINT agent_assets_pkey PRIMARY KEY (id);


--
-- Name: agent_async_tasks agent_async_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_pkey PRIMARY KEY (id);


--
-- Name: agent_canvas_run_operations agent_canvas_run_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_run_operations
    ADD CONSTRAINT agent_canvas_run_operations_pkey PRIMARY KEY (id);


--
-- Name: agent_canvas_runs agent_canvas_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_runs
    ADD CONSTRAINT agent_canvas_runs_pkey PRIMARY KEY (id);


--
-- Name: agent_canvas_sessions agent_canvas_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_sessions
    ADD CONSTRAINT agent_canvas_sessions_pkey PRIMARY KEY (id);


--
-- Name: agent_checkpoints agent_checkpoints_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_checkpoints
    ADD CONSTRAINT agent_checkpoints_pkey PRIMARY KEY (id);


--
-- Name: agent_conversation_asset_groups agent_conversation_asset_groups_conversation_id_group_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_conversation_asset_groups
    ADD CONSTRAINT agent_conversation_asset_groups_conversation_id_group_id_key UNIQUE (conversation_id, group_id);


--
-- Name: agent_conversation_asset_groups agent_conversation_asset_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_conversation_asset_groups
    ADD CONSTRAINT agent_conversation_asset_groups_pkey PRIMARY KEY (id);


--
-- Name: agent_document_index agent_document_index_asset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_document_index
    ADD CONSTRAINT agent_document_index_asset_id_key UNIQUE (asset_id);


--
-- Name: agent_document_index agent_document_index_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_document_index
    ADD CONSTRAINT agent_document_index_pkey PRIMARY KEY (id);


--
-- Name: agent_external_actions agent_external_actions_action_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_external_actions
    ADD CONSTRAINT agent_external_actions_action_key_key UNIQUE (action_key);


--
-- Name: agent_external_actions agent_external_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_external_actions
    ADD CONSTRAINT agent_external_actions_pkey PRIMARY KEY (id);


--
-- Name: agent_global_memories agent_global_memories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_global_memories
    ADD CONSTRAINT agent_global_memories_pkey PRIMARY KEY (user_id);


--
-- Name: agent_handoffs_archive agent_handoffs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_handoffs_archive
    ADD CONSTRAINT agent_handoffs_pkey PRIMARY KEY (id);


--
-- Name: agent_loop_runs_archive agent_loop_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_loop_runs_archive
    ADD CONSTRAINT agent_loop_runs_pkey PRIMARY KEY (id);


--
-- Name: agent_loop_steps_archive agent_loop_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_loop_steps_archive
    ADD CONSTRAINT agent_loop_steps_pkey PRIMARY KEY (id);


--
-- Name: agent_media_critiques agent_media_critiques_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_media_critiques
    ADD CONSTRAINT agent_media_critiques_pkey PRIMARY KEY (id);


--
-- Name: agent_memories agent_memories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_memories
    ADD CONSTRAINT agent_memories_pkey PRIMARY KEY (id);


--
-- Name: agent_memory_settings agent_memory_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_memory_settings
    ADD CONSTRAINT agent_memory_settings_pkey PRIMARY KEY (user_id);


--
-- Name: agent_messages agent_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_messages
    ADD CONSTRAINT agent_messages_pkey PRIMARY KEY (id);


--
-- Name: agent_packages agent_packages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_packages
    ADD CONSTRAINT agent_packages_pkey PRIMARY KEY (id);


--
-- Name: agent_recovery_leases agent_recovery_leases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_recovery_leases
    ADD CONSTRAINT agent_recovery_leases_pkey PRIMARY KEY (resource_type, resource_id);


--
-- Name: agent_sandbox_sessions agent_sandbox_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_sandbox_sessions
    ADD CONSTRAINT agent_sandbox_sessions_pkey PRIMARY KEY (conversation_id);


--
-- Name: agent_subagent_runs agent_subagent_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_subagent_runs
    ADD CONSTRAINT agent_subagent_runs_pkey PRIMARY KEY (id);


--
-- Name: agent_task_edges_archive agent_task_edges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_edges_archive
    ADD CONSTRAINT agent_task_edges_pkey PRIMARY KEY (id);


--
-- Name: agent_task_edges_archive agent_task_edges_plan_id_from_node_id_to_node_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_edges_archive
    ADD CONSTRAINT agent_task_edges_plan_id_from_node_id_to_node_id_key UNIQUE (plan_id, from_node_id, to_node_id);


--
-- Name: agent_task_nodes_archive agent_task_nodes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_nodes_archive
    ADD CONSTRAINT agent_task_nodes_pkey PRIMARY KEY (id);


--
-- Name: agent_task_nodes_archive agent_task_nodes_plan_id_node_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_nodes_archive
    ADD CONSTRAINT agent_task_nodes_plan_id_node_id_key UNIQUE (plan_id, node_id);


--
-- Name: agent_task_plans_archive agent_task_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_plans_archive
    ADD CONSTRAINT agent_task_plans_pkey PRIMARY KEY (id);


--
-- Name: agent_timeline_manifests agent_timeline_manifests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_timeline_manifests
    ADD CONSTRAINT agent_timeline_manifests_pkey PRIMARY KEY (id);


--
-- Name: agent_tool_invocations agent_tool_invocations_invocation_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_tool_invocations
    ADD CONSTRAINT agent_tool_invocations_invocation_key_key UNIQUE (invocation_key);


--
-- Name: agent_tool_invocations agent_tool_invocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_tool_invocations
    ADD CONSTRAINT agent_tool_invocations_pkey PRIMARY KEY (id);


--
-- Name: agent_turn_events agent_turn_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_turn_events
    ADD CONSTRAINT agent_turn_events_pkey PRIMARY KEY (id);


--
-- Name: agent_turn_events agent_turn_events_turn_id_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_turn_events
    ADD CONSTRAINT agent_turn_events_turn_id_sequence_key UNIQUE (turn_id, sequence);


--
-- Name: agent_turns agent_turns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_turns
    ADD CONSTRAINT agent_turns_pkey PRIMARY KEY (id);


--
-- Name: agent_workflow_runs agent_workflow_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_workflow_runs
    ADD CONSTRAINT agent_workflow_runs_pkey PRIMARY KEY (id);


--
-- Name: agent_workflow_steps agent_workflow_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_workflow_steps
    ADD CONSTRAINT agent_workflow_steps_pkey PRIMARY KEY (id);


--
-- Name: agent_workflow_steps agent_workflow_steps_workflow_run_id_step_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_workflow_steps
    ADD CONSTRAINT agent_workflow_steps_workflow_run_id_step_index_key UNIQUE (workflow_run_id, step_index);


--
-- Name: agent_workflow_steps agent_workflow_steps_workflow_run_id_step_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_workflow_steps
    ADD CONSTRAINT agent_workflow_steps_workflow_run_id_step_key_key UNIQUE (workflow_run_id, step_key);


--
-- Name: agentscope_sessions agentscope_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agentscope_sessions
    ADD CONSTRAINT agentscope_sessions_pkey PRIMARY KEY (session_id, state_key, item_index);


--
-- Name: agentscope_store agentscope_store_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agentscope_store
    ADD CONSTRAINT agentscope_store_pkey PRIMARY KEY (namespace_path, item_key);


--
-- Name: asset_images_archive asset_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_images_archive
    ADD CONSTRAINT asset_images_pkey PRIMARY KEY (id);


--
-- Name: asset_outbox asset_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_outbox
    ADD CONSTRAINT asset_outbox_pkey PRIMARY KEY (event_id);


--
-- Name: asset_outbox asset_outbox_producer_generation_record_id_result_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_outbox
    ADD CONSTRAINT asset_outbox_producer_generation_record_id_result_index_key UNIQUE (producer, generation_record_id, result_index);


--
-- Name: assets_archive assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assets_archive
    ADD CONSTRAINT assets_pkey PRIMARY KEY (id);


--
-- Name: canvas_assets canvas_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_assets
    ADD CONSTRAINT canvas_assets_pkey PRIMARY KEY (id);


--
-- Name: canvas_execution_nodes canvas_execution_nodes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_execution_nodes
    ADD CONSTRAINT canvas_execution_nodes_pkey PRIMARY KEY (id);


--
-- Name: canvas_executions canvas_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_executions
    ADD CONSTRAINT canvas_executions_pkey PRIMARY KEY (id);


--
-- Name: canvas_graph_operations canvas_graph_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_graph_operations
    ADD CONSTRAINT canvas_graph_operations_pkey PRIMARY KEY (operation_key);


--
-- Name: canvas_job_events canvas_job_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_job_events
    ADD CONSTRAINT canvas_job_events_pkey PRIMARY KEY (job_id, seq);


--
-- Name: canvas_jobs canvas_jobs_client_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_jobs
    ADD CONSTRAINT canvas_jobs_client_request_id_key UNIQUE (client_request_id);


--
-- Name: canvas_jobs canvas_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_jobs
    ADD CONSTRAINT canvas_jobs_pkey PRIMARY KEY (id);


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: morrow_canvas_graphs morrow_canvas_graphs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.morrow_canvas_graphs
    ADD CONSTRAINT morrow_canvas_graphs_pkey PRIMARY KEY (canvas_id);


--
-- Name: morrow_canvases morrow_canvases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.morrow_canvases
    ADD CONSTRAINT morrow_canvases_pkey PRIMARY KEY (id);


--
-- Name: project_creative_contexts project_creative_contexts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_creative_contexts
    ADD CONSTRAINT project_creative_contexts_pkey PRIMARY KEY (project_id);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: scene_asset_links scene_asset_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_asset_links
    ADD CONSTRAINT scene_asset_links_pkey PRIMARY KEY (scene_id, asset_id, purpose);


--
-- Name: scene_assets scene_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_assets
    ADD CONSTRAINT scene_assets_pkey PRIMARY KEY (id);


--
-- Name: scene_material_group_snapshots scene_material_group_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_material_group_snapshots
    ADD CONSTRAINT scene_material_group_snapshots_pkey PRIMARY KEY (scene_id, snapshot_id);


--
-- Name: scene_prompts scene_prompts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_prompts
    ADD CONSTRAINT scene_prompts_pkey PRIMARY KEY (scene_id);


--
-- Name: scene_reference_images scene_reference_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_reference_images
    ADD CONSTRAINT scene_reference_images_pkey PRIMARY KEY (id);


--
-- Name: scenes scenes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scenes
    ADD CONSTRAINT scenes_pkey PRIMARY KEY (id);


--
-- Name: storage_cleanup_queue storage_cleanup_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_cleanup_queue
    ADD CONSTRAINT storage_cleanup_queue_pkey PRIMARY KEY (id);


--
-- Name: storyboard_image_task storyboard_image_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_image_task
    ADD CONSTRAINT storyboard_image_task_pkey PRIMARY KEY (id);


--
-- Name: storyboard_runs storyboard_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_runs
    ADD CONSTRAINT storyboard_runs_pkey PRIMARY KEY (id);


--
-- Name: storyboard_shot_changes storyboard_shot_changes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_shot_changes
    ADD CONSTRAINT storyboard_shot_changes_pkey PRIMARY KEY (id);


--
-- Name: storyboard_shot_records storyboard_shot_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_shot_records
    ADD CONSTRAINT storyboard_shot_records_pkey PRIMARY KEY (id);


--
-- Name: storyboard_shot_records storyboard_shot_records_run_id_phase_shot_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_shot_records
    ADD CONSTRAINT storyboard_shot_records_run_id_phase_shot_id_key UNIQUE (run_id, phase, shot_id);


--
-- Name: storyboard_video_queue storyboard_video_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_video_queue
    ADD CONSTRAINT storyboard_video_queue_pkey PRIMARY KEY (id);


--
-- Name: agent_canvas_run_operations uq_agent_canvas_run_operations_run_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_run_operations
    ADD CONSTRAINT uq_agent_canvas_run_operations_run_key UNIQUE (run_id, operation_key);


--
-- Name: agent_canvas_runs uq_agent_canvas_runs_turn_tool; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_runs
    ADD CONSTRAINT uq_agent_canvas_runs_turn_tool UNIQUE (turn_id, tool_call_id);


--
-- Name: user_feedback user_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_feedback
    ADD CONSTRAINT user_feedback_pkey PRIMARY KEY (id);


--
-- Name: user_notifications user_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications
    ADD CONSTRAINT user_notifications_pkey PRIMARY KEY (id);


--
-- Name: user_settings user_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_pkey PRIMARY KEY (user_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_agentscope_sessions_updated; Type: INDEX; Schema: agentscope; Owner: -
--

CREATE INDEX idx_agentscope_sessions_updated ON agentscope.agentscope_sessions USING btree (updated_at DESC);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON asset.flyway_schema_history USING btree (success);


--
-- Name: idx_library_asset_folders_folder; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_asset_folders_folder ON asset.library_asset_folders USING btree (folder_id, asset_id);


--
-- Name: idx_library_assets_ai_description_trgm; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_ai_description_trgm ON asset.library_assets USING gin (ai_description public.gin_trgm_ops);


--
-- Name: idx_library_assets_conversation; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_conversation ON asset.library_assets USING btree (owner_user_id, owner_project_id, source_conversation_id, created_at DESC) WHERE (source_conversation_id IS NOT NULL);


--
-- Name: idx_library_assets_dominant_color; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_dominant_color ON asset.library_assets USING btree (dominant_color) WHERE (dominant_color IS NOT NULL);


--
-- Name: idx_library_assets_marks; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_marks ON asset.library_assets USING btree (owner_user_id, owner_project_id, rating DESC, flag, color_label) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_library_assets_name_trgm; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_name_trgm ON asset.library_assets USING gin (name public.gin_trgm_ops);


--
-- Name: idx_library_assets_owner_status; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_owner_status ON asset.library_assets USING btree (owner_user_id, status, updated_at DESC);


--
-- Name: idx_library_assets_project; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_project ON asset.library_assets USING btree (owner_project_id, status, updated_at DESC);


--
-- Name: idx_library_assets_scene_stack; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_scene_stack ON asset.library_assets USING btree (owner_user_id, source_conversation_id, source_scene_id) WHERE (source_scene_id IS NOT NULL);


--
-- Name: idx_library_assets_search; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_assets_search ON asset.library_assets USING gin (search_text public.gin_trgm_ops);


--
-- Name: idx_library_folders_parent; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_folders_parent ON asset.library_folders USING btree (owner_user_id, scope, owner_project_id, parent_id, sort_order, name);


--
-- Name: idx_library_import_jobs_group; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_import_jobs_group ON asset.library_import_jobs USING btree (group_id);


--
-- Name: idx_library_media_asset; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_media_asset ON asset.library_media USING btree (asset_id, sort_order);


--
-- Name: idx_library_media_blob; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_media_blob ON asset.library_media USING btree (blob_id);


--
-- Name: idx_library_project_links_project; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_project_links_project ON asset.library_project_links USING btree (project_id, created_at DESC);


--
-- Name: idx_library_saved_views_owner; Type: INDEX; Schema: asset; Owner: -
--

CREATE INDEX idx_library_saved_views_owner ON asset.library_saved_views USING btree (owner_user_id, scope, owner_project_id, pinned DESC, updated_at DESC);


--
-- Name: uq_library_asset_folders_asset_scope; Type: INDEX; Schema: asset; Owner: -
--

CREATE UNIQUE INDEX uq_library_asset_folders_asset_scope ON asset.library_asset_folders USING btree (owner_user_id, scope, COALESCE(owner_project_id, ''::text), asset_id);


--
-- Name: uq_library_folders_sibling_name; Type: INDEX; Schema: asset; Owner: -
--

CREATE UNIQUE INDEX uq_library_folders_sibling_name ON asset.library_folders USING btree (owner_user_id, scope, COALESCE(owner_project_id, ''::text), COALESCE(parent_id, ''::text), normalized_name);


--
-- Name: uq_library_saved_views_name; Type: INDEX; Schema: asset; Owner: -
--

CREATE UNIQUE INDEX uq_library_saved_views_name ON asset.library_saved_views USING btree (owner_user_id, scope, COALESCE(owner_project_id, ''::text), normalized_name);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_agent_archive_cleanups_cleaned_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_archive_cleanups_cleaned_at ON public.agent_archive_cleanups USING btree (cleaned_at DESC);


--
-- Name: idx_agent_assets_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_conv ON public.agent_assets USING btree (conversation_id, type);


--
-- Name: idx_agent_assets_conv_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_conv_created ON public.agent_assets USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_assets_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_created ON public.agent_assets USING btree (created_at DESC);


--
-- Name: idx_agent_assets_gen_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_gen_status_created ON public.agent_assets USING btree (gen_type, status, created_at DESC);


--
-- Name: idx_agent_assets_loop_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_loop_run ON public.agent_assets USING btree (loop_run_id);


--
-- Name: idx_agent_assets_plan_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_plan_node ON public.agent_assets USING btree (plan_id, node_id);


--
-- Name: idx_agent_assets_project_type_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_project_type_created ON public.agent_assets USING btree (project_id, type, created_at DESC);


--
-- Name: idx_agent_assets_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_task ON public.agent_assets USING btree (task_id);


--
-- Name: idx_agent_assets_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_assets_user_created ON public.agent_assets USING btree (user_id, created_at DESC);


--
-- Name: idx_agent_async_tasks_anchor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_anchor ON public.agent_async_tasks USING btree (anchor_message_id);


--
-- Name: idx_agent_async_tasks_conversation_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_conversation_created ON public.agent_async_tasks USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_async_tasks_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_parent ON public.agent_async_tasks USING btree (parent_task_id);


--
-- Name: idx_agent_async_tasks_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_status_created ON public.agent_async_tasks USING btree (status, created_at);


--
-- Name: idx_agent_async_tasks_user_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_user_updated ON public.agent_async_tasks USING btree (user_id, updated_at DESC);


--
-- Name: idx_agent_async_tasks_wakeup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_async_tasks_wakeup ON public.agent_async_tasks USING btree (wakeup_status, updated_at);


--
-- Name: idx_agent_canvas_run_operations_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_canvas_run_operations_run ON public.agent_canvas_run_operations USING btree (run_id, created_at);


--
-- Name: idx_agent_canvas_runs_canvas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_canvas_runs_canvas ON public.agent_canvas_runs USING btree (canvas_id);


--
-- Name: idx_agent_canvas_runs_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_canvas_runs_conversation ON public.agent_canvas_runs USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_canvas_sessions_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_canvas_sessions_conversation ON public.agent_canvas_sessions USING btree (conversation_id, updated_at DESC);


--
-- Name: idx_agent_canvas_sessions_turn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_canvas_sessions_turn ON public.agent_canvas_sessions USING btree (turn_id);


--
-- Name: idx_agent_checkpoints_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_checkpoints_conv ON public.agent_checkpoints USING btree (conversation_id, status);


--
-- Name: idx_agent_checkpoints_plan_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_checkpoints_plan_node ON public.agent_checkpoints USING btree (plan_id, node_id);


--
-- Name: idx_agent_checkpoints_status_expiration; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_checkpoints_status_expiration ON public.agent_checkpoints USING btree (status, expiration_time);


--
-- Name: idx_agent_checkpoints_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_checkpoints_token ON public.agent_checkpoints USING btree (form_token);


--
-- Name: idx_agent_conversation_asset_groups_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_conversation_asset_groups_conversation ON public.agent_conversation_asset_groups USING btree (conversation_id, created_at);


--
-- Name: idx_agent_document_index_conv_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_document_index_conv_updated ON public.agent_document_index USING btree (conversation_id, updated_at DESC);


--
-- Name: idx_agent_external_actions_checkpoint; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_external_actions_checkpoint ON public.agent_external_actions USING btree (checkpoint_id, tool_call_id);


--
-- Name: idx_agent_external_actions_status_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_external_actions_status_updated ON public.agent_external_actions USING btree (status, updated_at);


--
-- Name: idx_agent_handoffs_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_handoffs_run ON public.agent_handoffs_archive USING btree (loop_run_id, created_at);


--
-- Name: idx_agent_loop_runs_conversation_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_loop_runs_conversation_created ON public.agent_loop_runs_archive USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_loop_runs_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_loop_runs_trace ON public.agent_loop_runs_archive USING btree (trace_id);


--
-- Name: idx_agent_loop_steps_plan_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_loop_steps_plan_node ON public.agent_loop_steps_archive USING btree (plan_id, node_id, attempt_no);


--
-- Name: idx_agent_loop_steps_run_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_loop_steps_run_index ON public.agent_loop_steps_archive USING btree (loop_run_id, step_index);


--
-- Name: idx_agent_media_critiques_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_media_critiques_project ON public.agent_media_critiques USING btree (project_id, created_at DESC);


--
-- Name: idx_agent_media_critiques_turn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_media_critiques_turn ON public.agent_media_critiques USING btree (turn_id, created_at);


--
-- Name: idx_agent_memories_project_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_memories_project_updated ON public.agent_memories USING btree (project_id, updated_at DESC);


--
-- Name: idx_agent_memories_user_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_memories_user_updated ON public.agent_memories USING btree (user_id, updated_at DESC);


--
-- Name: idx_agent_messages_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_messages_conv ON public.agent_messages USING btree (conversation_id, created_at);


--
-- Name: idx_agent_messages_conversation_created_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_messages_conversation_created_id ON public.agent_messages USING btree (conversation_id, created_at DESC, id DESC);


--
-- Name: idx_agent_packages_kind_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_packages_kind_enabled ON public.agent_packages USING btree (kind, enabled);


--
-- Name: idx_agent_recovery_leases_next_attempt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_recovery_leases_next_attempt ON public.agent_recovery_leases USING btree (next_attempt_at, lease_until);


--
-- Name: idx_agent_sandbox_sessions_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_sandbox_sessions_expiry ON public.agent_sandbox_sessions USING btree (status, expires_at);


--
-- Name: idx_agent_subagent_runs_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_subagent_runs_conversation ON public.agent_subagent_runs USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_subagent_runs_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_subagent_runs_created_at ON public.agent_subagent_runs USING btree (created_at);


--
-- Name: idx_agent_subagent_runs_graph; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_subagent_runs_graph ON public.agent_subagent_runs USING btree (graph_id);


--
-- Name: idx_agent_task_nodes_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_task_nodes_ready ON public.agent_task_nodes_archive USING btree (plan_id, state, created_at);


--
-- Name: idx_agent_task_plans_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_task_plans_conversation ON public.agent_task_plans_archive USING btree (conversation_id, updated_at DESC);


--
-- Name: idx_agent_task_plans_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_task_plans_status ON public.agent_task_plans_archive USING btree (status, updated_at);


--
-- Name: idx_agent_timeline_manifests_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_timeline_manifests_project ON public.agent_timeline_manifests USING btree (project_id, updated_at DESC);


--
-- Name: idx_agent_tool_invocations_conversation_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_conversation_created ON public.agent_tool_invocations USING btree (conversation_id, created_at DESC);


--
-- Name: idx_agent_tool_invocations_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_status_created ON public.agent_tool_invocations USING btree (status, created_at);


--
-- Name: idx_agent_tool_invocations_task_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_task_created ON public.agent_tool_invocations USING btree (task_id, created_at DESC);


--
-- Name: idx_agent_tool_invocations_tool_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_tool_created ON public.agent_tool_invocations USING btree (tool_name, created_at DESC);


--
-- Name: idx_agent_tool_invocations_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_user_created ON public.agent_tool_invocations USING btree (user_id, created_at DESC);


--
-- Name: idx_agent_tool_invocations_user_started_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_tool_invocations_user_started_id ON public.agent_tool_invocations USING btree (user_id, started_at DESC, id DESC);


--
-- Name: idx_agent_turn_events_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_turn_events_created_at ON public.agent_turn_events USING btree (created_at);


--
-- Name: idx_agent_turn_events_replay; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_turn_events_replay ON public.agent_turn_events USING btree (turn_id, sequence);


--
-- Name: idx_agent_turns_conversation_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_turns_conversation_started ON public.agent_turns USING btree (conversation_id, started_at DESC);


--
-- Name: idx_agent_turns_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_turns_status ON public.agent_turns USING btree (status, started_at);


--
-- Name: idx_agent_workflow_runs_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_workflow_runs_conversation ON public.agent_workflow_runs USING btree (conversation_id, updated_at DESC);


--
-- Name: idx_agent_workflow_steps_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_workflow_steps_run ON public.agent_workflow_steps USING btree (workflow_run_id, step_index);


--
-- Name: idx_agentscope_sessions_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agentscope_sessions_updated ON public.agentscope_sessions USING btree (updated_at DESC);


--
-- Name: idx_asset_images_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_images_asset ON public.asset_images_archive USING btree (asset_id, sort_order);


--
-- Name: idx_asset_outbox_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_outbox_claim ON public.asset_outbox USING btree (status, next_retry_at, created_at);


--
-- Name: idx_asset_outbox_status_updated_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_outbox_status_updated_at ON public.asset_outbox USING btree (status, updated_at);


--
-- Name: idx_assets_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assets_project ON public.assets_archive USING btree (project_id);


--
-- Name: idx_assets_user_global; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assets_user_global ON public.assets_archive USING btree (user_id) WHERE (project_id IS NULL);


--
-- Name: idx_assets_user_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assets_user_project ON public.assets_archive USING btree (user_id, project_id);


--
-- Name: idx_canvas_assets_canvas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_assets_canvas ON public.canvas_assets USING btree (canvas_id, created_at DESC);


--
-- Name: idx_canvas_assets_library_sync; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_assets_library_sync ON public.canvas_assets USING btree (library_sync_state, created_at);


--
-- Name: idx_canvas_assets_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_assets_node ON public.canvas_assets USING btree (canvas_id, producer_node_id);


--
-- Name: idx_canvas_execution_nodes_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_execution_nodes_status ON public.canvas_execution_nodes USING btree (execution_id, status);


--
-- Name: idx_canvas_executions_canvas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_executions_canvas ON public.canvas_executions USING btree (canvas_id, created_at DESC);


--
-- Name: idx_canvas_graph_operations_canvas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_graph_operations_canvas ON public.canvas_graph_operations USING btree (canvas_id, created_at DESC);


--
-- Name: idx_canvas_graph_operations_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_graph_operations_run ON public.canvas_graph_operations USING btree (run_id, created_at DESC) WHERE (run_id IS NOT NULL);


--
-- Name: idx_canvas_job_events_job_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_job_events_job_seq ON public.canvas_job_events USING btree (job_id, seq);


--
-- Name: idx_canvas_jobs_batch_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_jobs_batch_status_created ON public.canvas_jobs USING btree (batch_id, status, created_at) WHERE (batch_id IS NOT NULL);


--
-- Name: idx_canvas_jobs_provider_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_jobs_provider_task ON public.canvas_jobs USING btree (provider_task_id) WHERE (provider_task_id IS NOT NULL);


--
-- Name: idx_canvas_jobs_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_jobs_status_created ON public.canvas_jobs USING btree (status, created_at);


--
-- Name: idx_canvas_jobs_status_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canvas_jobs_status_lease ON public.canvas_jobs USING btree (status, lease_expires_at) WHERE ((status)::text = 'running'::text);


--
-- Name: idx_conversations_user_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversations_user_project ON public.conversations USING btree (user_id, project_id, updated_at DESC);


--
-- Name: idx_morrow_canvases_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_morrow_canvases_project ON public.morrow_canvases USING btree (project_id);


--
-- Name: idx_morrow_canvases_project_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_morrow_canvases_project_updated ON public.morrow_canvases USING btree (project_id, updated_at DESC);


--
-- Name: idx_project_creative_context_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_creative_context_user ON public.project_creative_contexts USING btree (user_id, updated_at DESC);


--
-- Name: idx_projects_user_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_projects_user_draft ON public.projects USING btree (user_id, updated_at DESC) WHERE (status = 'draft'::text);


--
-- Name: idx_projects_user_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_projects_user_updated ON public.projects USING btree (user_id, updated_at DESC);


--
-- Name: idx_ref_images_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_images_scene ON public.scene_reference_images USING btree (scene_id, sort_order);


--
-- Name: idx_ref_images_scene_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_images_scene_source ON public.scene_reference_images USING btree (scene_id, source_type, sort_order);


--
-- Name: idx_scene_asset_links_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scene_asset_links_asset ON public.scene_asset_links USING btree (asset_id);


--
-- Name: idx_scene_assets_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scene_assets_asset ON public.scene_assets USING btree (asset_id);


--
-- Name: idx_scene_assets_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scene_assets_scene ON public.scene_assets USING btree (scene_id);


--
-- Name: idx_scene_material_group_snapshots_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scene_material_group_snapshots_group ON public.scene_material_group_snapshots USING btree (scene_id, group_id, purpose);


--
-- Name: idx_scenes_image_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scenes_image_task ON public.scenes USING btree (image_task_id);


--
-- Name: idx_scenes_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scenes_project ON public.scenes USING btree (project_id, scene_number);


--
-- Name: idx_scenes_project_conversation_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scenes_project_conversation_number ON public.scenes USING btree (project_id, conversation_id, scene_number);


--
-- Name: idx_scenes_video_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scenes_video_task ON public.scenes USING btree (video_task_id);


--
-- Name: idx_storage_cleanup_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storage_cleanup_pending ON public.storage_cleanup_queue USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_storyboard_image_task_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_image_task_project ON public.storyboard_image_task USING btree (project_id);


--
-- Name: idx_storyboard_image_task_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_image_task_scene ON public.storyboard_image_task USING btree (scene_id);


--
-- Name: idx_storyboard_image_task_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_image_task_status_created ON public.storyboard_image_task USING btree (status, created_at);


--
-- Name: idx_storyboard_runs_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_runs_owner ON public.storyboard_runs USING btree (user_id, updated_at DESC);


--
-- Name: idx_storyboard_runs_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_runs_project ON public.storyboard_runs USING btree (project_id, updated_at DESC);


--
-- Name: idx_storyboard_shot_changes_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_shot_changes_run ON public.storyboard_shot_changes USING btree (run_id, created_at DESC);


--
-- Name: idx_storyboard_shot_records_project_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_shot_records_project_scene ON public.storyboard_shot_records USING btree (project_scene_id);


--
-- Name: idx_storyboard_shot_records_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_shot_records_run ON public.storyboard_shot_records USING btree (run_id, phase, shot_ordinal);


--
-- Name: idx_storyboard_video_queue_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_video_queue_project ON public.storyboard_video_queue USING btree (project_id);


--
-- Name: idx_storyboard_video_queue_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_video_queue_scene ON public.storyboard_video_queue USING btree (scene_id);


--
-- Name: idx_storyboard_video_queue_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storyboard_video_queue_status_created ON public.storyboard_video_queue USING btree (status, created_at);


--
-- Name: idx_user_feedback_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_feedback_status_created ON public.user_feedback USING btree (status, created_at DESC);


--
-- Name: idx_user_feedback_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_feedback_user_created ON public.user_feedback USING btree (user_id, created_at DESC);


--
-- Name: idx_user_notifications_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_notifications_unread ON public.user_notifications USING btree (user_id, created_at DESC) WHERE (read_at IS NULL);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role);


--
-- Name: idx_users_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_status ON public.users USING btree (status);


--
-- Name: uk_agent_memories_user_project_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_agent_memories_user_project_hash ON public.agent_memories USING btree (user_id, project_id, content_hash);


--
-- Name: uk_agent_packages_kind_name_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_agent_packages_kind_name_version ON public.agent_packages USING btree (kind, name, version);


--
-- Name: uk_canvas_execution_nodes_position; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_canvas_execution_nodes_position ON public.canvas_execution_nodes USING btree (execution_id, "position");


--
-- Name: uq_agent_assets_idempotency_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_assets_idempotency_key ON public.agent_assets USING btree (idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uq_agent_async_tasks_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_async_tasks_idempotency ON public.agent_async_tasks USING btree (idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uq_agent_canvas_sessions_active_turn; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_canvas_sessions_active_turn ON public.agent_canvas_sessions USING btree (conversation_id, turn_id) WHERE ((status)::text = ANY (ARRAY[('pending_selection'::character varying)::text, ('pending_open'::character varying)::text, ('opening'::character varying)::text, ('ready'::character varying)::text]));


--
-- Name: uq_agent_messages_async_task; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_messages_async_task ON public.agent_messages USING btree (((metadata ->> 'asyncTaskId'::text))) WHERE (metadata ? 'asyncTaskId'::text);


--
-- Name: uq_agent_messages_wakeup_task; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_messages_wakeup_task ON public.agent_messages USING btree (((metadata ->> 'wakeupTaskId'::text))) WHERE (metadata ? 'wakeupTaskId'::text);


--
-- Name: uq_agent_turns_conversation_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_agent_turns_conversation_request ON public.agent_turns USING btree (conversation_id, client_request_id) WHERE (client_request_id IS NOT NULL);


--
-- Name: uq_canvas_assets_generation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_canvas_assets_generation ON public.canvas_assets USING btree (generation_record_id, object_url) WHERE (generation_record_id IS NOT NULL);


--
-- Name: uq_canvas_assets_library_alias; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_canvas_assets_library_alias ON public.canvas_assets USING btree (canvas_id, library_asset_id) WHERE (library_asset_id IS NOT NULL);


--
-- Name: uq_canvas_assets_object; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_canvas_assets_object ON public.canvas_assets USING btree (canvas_id, object_url);


--
-- Name: uq_storage_cleanup_object_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_storage_cleanup_object_pending ON public.storage_cleanup_queue USING btree (object_url) WHERE ((status)::text = ANY (ARRAY[('pending'::character varying)::text, ('running'::character varying)::text]));


--
-- Name: uq_user_notifications_dedupe; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_notifications_dedupe ON public.user_notifications USING btree (dedupe_key) WHERE (dedupe_key IS NOT NULL);


--
-- Name: library_ai_tag_suppressions_archive library_ai_tag_suppressions_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_ai_tag_suppressions_archive
    ADD CONSTRAINT library_ai_tag_suppressions_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_analysis_tasks library_analysis_tasks_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_analysis_tasks
    ADD CONSTRAINT library_analysis_tasks_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_asset_folders library_asset_folders_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_folders
    ADD CONSTRAINT library_asset_folders_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_asset_folders library_asset_folders_folder_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_folders
    ADD CONSTRAINT library_asset_folders_folder_id_fkey FOREIGN KEY (folder_id) REFERENCES asset.library_folders(id) ON DELETE RESTRICT;


--
-- Name: library_asset_tags library_asset_tags_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_tags
    ADD CONSTRAINT library_asset_tags_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_asset_tags library_asset_tags_tag_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_asset_tags
    ADD CONSTRAINT library_asset_tags_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES asset.library_tags(id) ON DELETE CASCADE;


--
-- Name: library_folders library_folders_parent_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_folders
    ADD CONSTRAINT library_folders_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES asset.library_folders(id) ON DELETE RESTRICT;


--
-- Name: library_group_items library_group_items_group_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_items
    ADD CONSTRAINT library_group_items_group_id_fkey FOREIGN KEY (group_id) REFERENCES asset.library_groups(id) ON DELETE CASCADE;


--
-- Name: library_group_items library_group_items_media_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_items
    ADD CONSTRAINT library_group_items_media_id_fkey FOREIGN KEY (media_id) REFERENCES asset.library_media(id);


--
-- Name: library_group_items library_group_items_member_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_items
    ADD CONSTRAINT library_group_items_member_asset_id_fkey FOREIGN KEY (member_asset_id) REFERENCES asset.library_assets(id);


--
-- Name: library_group_outputs library_group_outputs_group_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_outputs
    ADD CONSTRAINT library_group_outputs_group_id_fkey FOREIGN KEY (group_id) REFERENCES asset.library_groups(id) ON DELETE CASCADE;


--
-- Name: library_group_outputs library_group_outputs_result_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_outputs
    ADD CONSTRAINT library_group_outputs_result_asset_id_fkey FOREIGN KEY (result_asset_id) REFERENCES asset.library_assets(id);


--
-- Name: library_group_snapshot_items library_group_snapshot_items_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshot_items
    ADD CONSTRAINT library_group_snapshot_items_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES asset.library_group_snapshots(id) ON DELETE CASCADE;


--
-- Name: library_group_snapshots library_group_snapshots_group_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_group_snapshots
    ADD CONSTRAINT library_group_snapshots_group_id_fkey FOREIGN KEY (group_id) REFERENCES asset.library_groups(id) ON DELETE RESTRICT;


--
-- Name: library_groups library_groups_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_groups
    ADD CONSTRAINT library_groups_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_import_items library_import_items_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_items
    ADD CONSTRAINT library_import_items_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id);


--
-- Name: library_import_items library_import_items_job_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_items
    ADD CONSTRAINT library_import_items_job_id_fkey FOREIGN KEY (job_id) REFERENCES asset.library_import_jobs(id) ON DELETE CASCADE;


--
-- Name: library_import_jobs library_import_jobs_group_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_import_jobs
    ADD CONSTRAINT library_import_jobs_group_id_fkey FOREIGN KEY (group_id) REFERENCES asset.library_assets(id);


--
-- Name: library_media library_media_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_media
    ADD CONSTRAINT library_media_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: library_media library_media_blob_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_media
    ADD CONSTRAINT library_media_blob_id_fkey FOREIGN KEY (blob_id) REFERENCES asset.library_blobs(id);


--
-- Name: library_project_links library_project_links_asset_id_fkey; Type: FK CONSTRAINT; Schema: asset; Owner: -
--

ALTER TABLE ONLY asset.library_project_links
    ADD CONSTRAINT library_project_links_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset.library_assets(id) ON DELETE CASCADE;


--
-- Name: agent_archive_cleanups agent_archive_cleanups_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_archive_cleanups
    ADD CONSTRAINT agent_archive_cleanups_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_assets agent_assets_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_assets
    ADD CONSTRAINT agent_assets_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_assets agent_assets_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_assets
    ADD CONSTRAINT agent_assets_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: agent_async_tasks agent_async_tasks_anchor_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_anchor_message_id_fkey FOREIGN KEY (anchor_message_id) REFERENCES public.agent_messages(id) ON DELETE SET NULL;


--
-- Name: agent_async_tasks agent_async_tasks_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_async_tasks agent_async_tasks_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.agent_messages(id) ON DELETE SET NULL;


--
-- Name: agent_async_tasks agent_async_tasks_parent_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_parent_task_id_fkey FOREIGN KEY (parent_task_id) REFERENCES public.agent_async_tasks(id) ON DELETE SET NULL;


--
-- Name: agent_async_tasks agent_async_tasks_wakeup_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_async_tasks
    ADD CONSTRAINT agent_async_tasks_wakeup_message_id_fkey FOREIGN KEY (wakeup_message_id) REFERENCES public.agent_messages(id) ON DELETE SET NULL;


--
-- Name: agent_canvas_run_operations agent_canvas_run_operations_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_run_operations
    ADD CONSTRAINT agent_canvas_run_operations_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.agent_canvas_runs(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_runs agent_canvas_runs_canvas_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_runs
    ADD CONSTRAINT agent_canvas_runs_canvas_id_fkey FOREIGN KEY (canvas_id) REFERENCES public.morrow_canvases(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_runs agent_canvas_runs_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_runs
    ADD CONSTRAINT agent_canvas_runs_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_runs agent_canvas_runs_turn_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_runs
    ADD CONSTRAINT agent_canvas_runs_turn_id_fkey FOREIGN KEY (turn_id) REFERENCES public.agent_turns(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_sessions agent_canvas_sessions_canvas_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_sessions
    ADD CONSTRAINT agent_canvas_sessions_canvas_id_fkey FOREIGN KEY (canvas_id) REFERENCES public.morrow_canvases(id) ON DELETE SET NULL;


--
-- Name: agent_canvas_sessions agent_canvas_sessions_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_sessions
    ADD CONSTRAINT agent_canvas_sessions_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_sessions agent_canvas_sessions_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_sessions
    ADD CONSTRAINT agent_canvas_sessions_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: agent_canvas_sessions agent_canvas_sessions_turn_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_canvas_sessions
    ADD CONSTRAINT agent_canvas_sessions_turn_id_fkey FOREIGN KEY (turn_id) REFERENCES public.agent_turns(id) ON DELETE CASCADE;


--
-- Name: agent_checkpoints agent_checkpoints_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_checkpoints
    ADD CONSTRAINT agent_checkpoints_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_conversation_asset_groups agent_conversation_asset_groups_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_conversation_asset_groups
    ADD CONSTRAINT agent_conversation_asset_groups_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_document_index agent_document_index_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_document_index
    ADD CONSTRAINT agent_document_index_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.agent_assets(id) ON DELETE CASCADE;


--
-- Name: agent_document_index agent_document_index_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_document_index
    ADD CONSTRAINT agent_document_index_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_external_actions agent_external_actions_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_external_actions
    ADD CONSTRAINT agent_external_actions_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_handoffs_archive agent_handoffs_loop_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_handoffs_archive
    ADD CONSTRAINT agent_handoffs_loop_run_id_fkey FOREIGN KEY (loop_run_id) REFERENCES public.agent_loop_runs_archive(id) ON DELETE CASCADE;


--
-- Name: agent_loop_runs_archive agent_loop_runs_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_loop_runs_archive
    ADD CONSTRAINT agent_loop_runs_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_loop_steps_archive agent_loop_steps_loop_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_loop_steps_archive
    ADD CONSTRAINT agent_loop_steps_loop_run_id_fkey FOREIGN KEY (loop_run_id) REFERENCES public.agent_loop_runs_archive(id) ON DELETE CASCADE;


--
-- Name: agent_media_critiques agent_media_critiques_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_media_critiques
    ADD CONSTRAINT agent_media_critiques_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_media_critiques agent_media_critiques_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_media_critiques
    ADD CONSTRAINT agent_media_critiques_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: agent_media_critiques agent_media_critiques_turn_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_media_critiques
    ADD CONSTRAINT agent_media_critiques_turn_id_fkey FOREIGN KEY (turn_id) REFERENCES public.agent_turns(id) ON DELETE SET NULL;


--
-- Name: agent_messages agent_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_messages
    ADD CONSTRAINT agent_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_sandbox_sessions agent_sandbox_sessions_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_sandbox_sessions
    ADD CONSTRAINT agent_sandbox_sessions_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_task_edges_archive agent_task_edges_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_edges_archive
    ADD CONSTRAINT agent_task_edges_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.agent_task_plans_archive(id) ON DELETE CASCADE;


--
-- Name: agent_task_nodes_archive agent_task_nodes_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_nodes_archive
    ADD CONSTRAINT agent_task_nodes_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.agent_task_plans_archive(id) ON DELETE CASCADE;


--
-- Name: agent_task_plans_archive agent_task_plans_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_task_plans_archive
    ADD CONSTRAINT agent_task_plans_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_timeline_manifests agent_timeline_manifests_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_timeline_manifests
    ADD CONSTRAINT agent_timeline_manifests_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_timeline_manifests agent_timeline_manifests_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_timeline_manifests
    ADD CONSTRAINT agent_timeline_manifests_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: agent_timeline_manifests agent_timeline_manifests_turn_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_timeline_manifests
    ADD CONSTRAINT agent_timeline_manifests_turn_id_fkey FOREIGN KEY (turn_id) REFERENCES public.agent_turns(id) ON DELETE SET NULL;


--
-- Name: agent_tool_invocations agent_tool_invocations_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_tool_invocations
    ADD CONSTRAINT agent_tool_invocations_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE SET NULL;


--
-- Name: agent_turn_events agent_turn_events_turn_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_turn_events
    ADD CONSTRAINT agent_turn_events_turn_id_fkey FOREIGN KEY (turn_id) REFERENCES public.agent_turns(id) ON DELETE CASCADE;


--
-- Name: agent_turns agent_turns_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_turns
    ADD CONSTRAINT agent_turns_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: agent_workflow_steps agent_workflow_steps_workflow_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_workflow_steps
    ADD CONSTRAINT agent_workflow_steps_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES public.agent_workflow_runs(id) ON DELETE CASCADE;


--
-- Name: asset_images_archive asset_images_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_images_archive
    ADD CONSTRAINT asset_images_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.assets_archive(id) ON DELETE CASCADE;


--
-- Name: assets_archive assets_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assets_archive
    ADD CONSTRAINT assets_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: canvas_assets canvas_assets_canvas_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_assets
    ADD CONSTRAINT canvas_assets_canvas_id_fkey FOREIGN KEY (canvas_id) REFERENCES public.morrow_canvases(id) ON DELETE CASCADE;


--
-- Name: canvas_execution_nodes canvas_execution_nodes_execution_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_execution_nodes
    ADD CONSTRAINT canvas_execution_nodes_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES public.canvas_executions(id) ON DELETE CASCADE;


--
-- Name: canvas_graph_operations canvas_graph_operations_canvas_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_graph_operations
    ADD CONSTRAINT canvas_graph_operations_canvas_id_fkey FOREIGN KEY (canvas_id) REFERENCES public.morrow_canvases(id) ON DELETE CASCADE;


--
-- Name: canvas_job_events canvas_job_events_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canvas_job_events
    ADD CONSTRAINT canvas_job_events_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.canvas_jobs(id) ON DELETE CASCADE;


--
-- Name: conversations conversations_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: morrow_canvas_graphs morrow_canvas_graphs_canvas_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.morrow_canvas_graphs
    ADD CONSTRAINT morrow_canvas_graphs_canvas_id_fkey FOREIGN KEY (canvas_id) REFERENCES public.morrow_canvases(id) ON DELETE CASCADE;


--
-- Name: morrow_canvases morrow_canvases_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.morrow_canvases
    ADD CONSTRAINT morrow_canvases_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_creative_contexts project_creative_contexts_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_creative_contexts
    ADD CONSTRAINT project_creative_contexts_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: scene_asset_links scene_asset_links_scene_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_asset_links
    ADD CONSTRAINT scene_asset_links_scene_id_fkey FOREIGN KEY (scene_id) REFERENCES public.scenes(id) ON DELETE CASCADE;


--
-- Name: scene_assets scene_assets_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_assets
    ADD CONSTRAINT scene_assets_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.assets_archive(id) ON DELETE CASCADE;


--
-- Name: scene_assets scene_assets_scene_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_assets
    ADD CONSTRAINT scene_assets_scene_id_fkey FOREIGN KEY (scene_id) REFERENCES public.scenes(id) ON DELETE CASCADE;


--
-- Name: scene_material_group_snapshots scene_material_group_snapshots_scene_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_material_group_snapshots
    ADD CONSTRAINT scene_material_group_snapshots_scene_id_fkey FOREIGN KEY (scene_id) REFERENCES public.scenes(id) ON DELETE CASCADE;


--
-- Name: scene_prompts scene_prompts_scene_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_prompts
    ADD CONSTRAINT scene_prompts_scene_id_fkey FOREIGN KEY (scene_id) REFERENCES public.scenes(id) ON DELETE CASCADE;


--
-- Name: scene_reference_images scene_reference_images_scene_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scene_reference_images
    ADD CONSTRAINT scene_reference_images_scene_id_fkey FOREIGN KEY (scene_id) REFERENCES public.scenes(id) ON DELETE CASCADE;


--
-- Name: scenes scenes_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scenes
    ADD CONSTRAINT scenes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: storyboard_shot_records storyboard_shot_records_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storyboard_shot_records
    ADD CONSTRAINT storyboard_shot_records_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.storyboard_runs(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict N5gApLhPg0VyuBfk8GfgetenjTQgBRRfWCfFdgHOOCJg29qUp5hA0OERtf4hwq3

