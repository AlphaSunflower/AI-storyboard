-- 文档读取索引：保存首次读取后的摘要和解析元数据，不保存全文。
ALTER TABLE public.agent_assets
    ADD COLUMN IF NOT EXISTS original_name text,
    ADD COLUMN IF NOT EXISTS mime_type character varying(128);

CREATE TABLE IF NOT EXISTS public.agent_document_index (
    id              text PRIMARY KEY DEFAULT gen_random_uuid()::text,
    asset_id        text NOT NULL UNIQUE REFERENCES public.agent_assets(id) ON DELETE CASCADE,
    conversation_id text NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
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

CREATE INDEX IF NOT EXISTS idx_agent_document_index_conv_updated
    ON public.agent_document_index (conversation_id, updated_at DESC);
