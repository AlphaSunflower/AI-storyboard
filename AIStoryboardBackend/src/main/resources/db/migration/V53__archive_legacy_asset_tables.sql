-- V53: 归档旧 AI 资产表（在 V52 之后、后端重启完成后执行）
--
-- 背景：
--   2026-09-30 已把 public.assets / asset_images 按原 ID 全量迁移到
--   asset.library_assets / asset.library_media；后端 getProjectAssets 已改为
--   直接读 asset-service。旧表只保留作历史对照，不再被代码读取。
--
-- 为什么单独一版：
--   V52 执行时旧后端进程可能仍在运行并调用 getProjectAssets；改名后旧代码会读不到表。
--   等 8082 后端重启到新版本后再执行本脚本。
--
-- 手动执行：
--   psql -h 127.0.0.1 -U postgres -d ai_storyboard -f V53__archive_legacy_asset_tables.sql
--
-- 注意：public.scene_assets 仍保留（其 asset_id 外键会自动跟随 assets 改名），
--      后续把最后 1 行分镜关联迁到 scene_reference_images 后再单独归档。
--
-- 回滚：
--   ALTER TABLE public.assets_archive RENAME TO assets;
--   ALTER TABLE public.asset_images_archive RENAME TO asset_images;

BEGIN;

ALTER TABLE IF EXISTS public.assets       RENAME TO assets_archive;
ALTER TABLE IF EXISTS public.asset_images RENAME TO asset_images_archive;

COMMIT;
