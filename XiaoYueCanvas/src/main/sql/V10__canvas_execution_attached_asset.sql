-- V10__canvas_execution_attached_asset.sql
-- 服务端批次把生成结果直接挂回画布图：记录挂上去的画布资产 id（= canvas_assets.id）。
BEGIN;

ALTER TABLE canvas_execution_nodes
    ADD COLUMN IF NOT EXISTS attached_asset_id TEXT;

COMMIT;
