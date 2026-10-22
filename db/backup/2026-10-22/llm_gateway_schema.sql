--
-- PostgreSQL database dump
--

\restrict h1GHWsh1Qs7Q1rdq7f4rcYZbvjVoGAtp1SSmVu42UEL8Loqib53Gjb2VkeCbiO3

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

-- 依赖扩展
CREATE EXTENSION IF NOT EXISTS plpgsql;

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
-- Name: admin_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_user (
    id character varying(64) NOT NULL,
    username character varying(64) NOT NULL,
    password_hash text NOT NULL,
    role character varying(32) DEFAULT 'admin'::character varying NOT NULL,
    status character varying(32) DEFAULT 'enabled'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: call_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.call_log (
    id character varying(64) NOT NULL,
    model character varying(128),
    channel_id character varying(64),
    status character varying(32),
    duration_ms bigint,
    error text,
    video_url text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    task_id character varying(128),
    call_id character varying(128),
    upstream_model character varying(128),
    attempt integer,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer
);


--
-- Name: call_log_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.call_log_archive (
    id character varying(64) NOT NULL,
    model character varying(128),
    channel_id character varying(64),
    status character varying(32),
    duration_ms bigint,
    error text,
    video_url text,
    task_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    call_id character varying(128)
);


--
-- Name: channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.channel (
    id character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    type character varying(32) DEFAULT 'openai_compatible'::character varying NOT NULL,
    base_url character varying(512) NOT NULL,
    api_key text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    models text,
    weight integer DEFAULT 1 NOT NULL,
    timeout_ms integer,
    max_retries integer DEFAULT 0 NOT NULL,
    last_error text,
    last_checked_at timestamp with time zone
);


--
-- Name: channel_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.channel_key (
    id character varying(64) NOT NULL,
    channel_id character varying(64) NOT NULL,
    label character varying(64),
    api_key text NOT NULL,
    weight integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    fail_count integer DEFAULT 0 NOT NULL,
    cooldown_until timestamp with time zone,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: gateway_api_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gateway_api_key (
    id character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    key_hash character varying(128) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: generation_idempotency_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.generation_idempotency_archive (
    id text DEFAULT (gen_random_uuid())::text CONSTRAINT generation_idempotency_id_not_null NOT NULL,
    idempotency_key text CONSTRAINT generation_idempotency_idempotency_key_not_null NOT NULL,
    request_fingerprint text CONSTRAINT generation_idempotency_request_fingerprint_not_null NOT NULL,
    media_type character varying(16) CONSTRAINT generation_idempotency_media_type_not_null NOT NULL,
    status character varying(32) CONSTRAINT generation_idempotency_status_not_null NOT NULL,
    task_id text,
    response_body text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT generation_idempotency_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT generation_idempotency_updated_at_not_null NOT NULL
);


--
-- Name: model_fallback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_fallback (
    id character varying(64) NOT NULL,
    model_name character varying(128) NOT NULL,
    type character varying(32) DEFAULT 'text'::character varying NOT NULL,
    fallback_model character varying(128) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: model_params; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_params (
    id character varying(32) NOT NULL,
    model_name character varying(255) NOT NULL,
    type character varying(32) DEFAULT 'text'::character varying NOT NULL,
    temperature text,
    max_tokens integer,
    top_p text,
    n_min integer,
    n_max integer,
    n_default integer,
    sizes text,
    size_default text,
    qualities text,
    quality_default text,
    styles text,
    style_default text,
    durations text,
    duration_default text,
    resolutions text,
    resolution_default text,
    aspect_ratios text,
    aspect_ratio_default text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    ref_images_min integer,
    ref_images_max integer,
    ref_videos_min integer,
    ref_videos_max integer,
    audio_count_min integer,
    audio_count_max integer,
    audio_segment_duration_min integer,
    audio_segment_duration_max integer,
    video_segment_duration_min integer,
    video_segment_duration_max integer,
    max_total_duration integer,
    max_total_files integer,
    max_video_size_mb integer,
    max_image_size_mb integer,
    max_audio_size_mb integer,
    max_request_body_mb integer,
    max_prompt_chars integer,
    is_default boolean DEFAULT false,
    params_schema text,
    ollama_params text,
    context_window integer
);


--
-- Name: model_route; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_route (
    id character varying(64) NOT NULL,
    model_name character varying(128) NOT NULL,
    channel_id character varying(64) NOT NULL,
    default_params text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    type character varying(32) DEFAULT 'text'::character varying NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    weight integer DEFAULT 1 NOT NULL,
    upstream_model character varying(128),
    timeout_ms integer,
    max_retries integer,
    param_overrides text
);


--
-- Name: sys_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_config (
    id character varying(64) NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value text NOT NULL,
    remark character varying(255),
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


--
-- Name: admin_user admin_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_user
    ADD CONSTRAINT admin_user_pkey PRIMARY KEY (id);


--
-- Name: admin_user admin_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_user
    ADD CONSTRAINT admin_user_username_key UNIQUE (username);


--
-- Name: call_log_archive call_log_archive_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.call_log_archive
    ADD CONSTRAINT call_log_archive_pkey PRIMARY KEY (id);


--
-- Name: call_log call_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.call_log
    ADD CONSTRAINT call_log_pkey PRIMARY KEY (id);


--
-- Name: channel_key channel_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_key
    ADD CONSTRAINT channel_key_pkey PRIMARY KEY (id);


--
-- Name: channel channel_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel
    ADD CONSTRAINT channel_pkey PRIMARY KEY (id);


--
-- Name: gateway_api_key gateway_api_key_key_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gateway_api_key
    ADD CONSTRAINT gateway_api_key_key_hash_key UNIQUE (key_hash);


--
-- Name: gateway_api_key gateway_api_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gateway_api_key
    ADD CONSTRAINT gateway_api_key_pkey PRIMARY KEY (id);


--
-- Name: generation_idempotency_archive generation_idempotency_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.generation_idempotency_archive
    ADD CONSTRAINT generation_idempotency_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: generation_idempotency_archive generation_idempotency_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.generation_idempotency_archive
    ADD CONSTRAINT generation_idempotency_pkey PRIMARY KEY (id);


--
-- Name: model_fallback model_fallback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_fallback
    ADD CONSTRAINT model_fallback_pkey PRIMARY KEY (id);


--
-- Name: model_params model_params_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_params
    ADD CONSTRAINT model_params_pkey PRIMARY KEY (id);


--
-- Name: model_route model_route_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_pkey PRIMARY KEY (id);


--
-- Name: sys_config sys_config_config_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_config
    ADD CONSTRAINT sys_config_config_key_key UNIQUE (config_key);


--
-- Name: sys_config sys_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_config
    ADD CONSTRAINT sys_config_pkey PRIMARY KEY (id);


--
-- Name: idx_call_log_archive_call_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_archive_call_id ON public.call_log_archive USING btree (call_id);


--
-- Name: idx_call_log_archive_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_archive_created_at ON public.call_log_archive USING btree (created_at DESC);


--
-- Name: idx_call_log_archive_model_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_archive_model_created ON public.call_log_archive USING btree (model, created_at DESC);


--
-- Name: idx_call_log_archive_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_archive_task_id ON public.call_log_archive USING btree (task_id);


--
-- Name: idx_call_log_call_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_call_id ON public.call_log USING btree (call_id);


--
-- Name: idx_call_log_channel_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_channel_created ON public.call_log USING btree (channel_id, created_at DESC);


--
-- Name: idx_call_log_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_created_at ON public.call_log USING btree (created_at DESC);


--
-- Name: idx_call_log_model_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_model_created ON public.call_log USING btree (model, created_at DESC);


--
-- Name: idx_call_log_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_call_log_task_id ON public.call_log USING btree (task_id);


--
-- Name: idx_channel_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_channel_enabled ON public.channel USING btree (enabled, priority);


--
-- Name: idx_channel_key_channel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_channel_key_channel ON public.channel_key USING btree (channel_id);


--
-- Name: idx_generation_idempotency_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_generation_idempotency_created ON public.generation_idempotency_archive USING btree (created_at DESC);


--
-- Name: idx_model_fallback_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_fallback_model ON public.model_fallback USING btree (model_name, type, sort_order);


--
-- Name: idx_model_route_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_route_model ON public.model_route USING btree (model_name) WHERE (deleted = false);


--
-- Name: idx_model_route_model_channel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_route_model_channel ON public.model_route USING btree (model_name, channel_id) WHERE (deleted = false);


--
-- Name: uk_model_fallback; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_model_fallback ON public.model_fallback USING btree (model_name, type, fallback_model);


--
-- Name: uk_model_params_default_per_type; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_model_params_default_per_type ON public.model_params USING btree (type) WHERE (is_default = true);


--
-- Name: uk_model_params_name_type; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_model_params_name_type ON public.model_params USING btree (model_name, type);


--
-- Name: channel_key channel_key_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_key
    ADD CONSTRAINT channel_key_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES public.channel(id);


--
-- Name: model_route model_route_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_route
    ADD CONSTRAINT model_route_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES public.channel(id);


--
-- PostgreSQL database dump complete
--

\unrestrict h1GHWsh1Qs7Q1rdq7f4rcYZbvjVoGAtp1SSmVu42UEL8Loqib53Gjb2VkeCbiO3

