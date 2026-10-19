-- 智能搜索：主色 + pg_trgm 模糊匹配。
-- pg_trgm 已在本地安装；CREATE EXTENSION 需要超级用户，缺失时搜索接口会退化为 ILIKE。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE asset.library_assets
    ADD COLUMN IF NOT EXISTS dominant_color VARCHAR(7);

CREATE INDEX IF NOT EXISTS idx_library_assets_dominant_color
    ON asset.library_assets (dominant_color)
    WHERE dominant_color IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_library_assets_name_trgm
    ON asset.library_assets USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_library_assets_ai_description_trgm
    ON asset.library_assets USING gin (ai_description gin_trgm_ops);
