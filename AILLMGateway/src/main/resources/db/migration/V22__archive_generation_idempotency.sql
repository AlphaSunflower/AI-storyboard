-- V22: 归档无代码引用的 generation_idempotency（2026-09-22 后再无写入）
--
-- 手动执行：
--   psql -h 127.0.0.1 -U postgres -d llm_gateway -f V22__archive_generation_idempotency.sql
--
-- 回滚：
--   ALTER TABLE public.generation_idempotency_archive RENAME TO generation_idempotency;

ALTER TABLE IF EXISTS public.generation_idempotency RENAME TO generation_idempotency_archive;
