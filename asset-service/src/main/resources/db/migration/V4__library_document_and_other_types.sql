-- 项目库支持任意文件：asset_type / blobs.media_type 增加 DOCUMENT、OTHER。
-- 内联 CHECK 由 PostgreSQL 自动命名，这里按列动态定位后重建为具名约束。

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
                 JOIN pg_class rel ON rel.oid = con.conrelid
                 JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                 JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
        WHERE nsp.nspname = 'asset'
          AND rel.relname = 'library_assets'
          AND att.attname = 'asset_type'
          AND con.contype = 'c'
        LOOP
            EXECUTE format('ALTER TABLE asset.library_assets DROP CONSTRAINT %I', constraint_name);
        END LOOP;
END $$;

ALTER TABLE asset.library_assets
    ADD CONSTRAINT library_assets_asset_type_check
        CHECK (asset_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER', 'AI_ASSET', 'GROUP'));

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
                 JOIN pg_class rel ON rel.oid = con.conrelid
                 JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                 JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
        WHERE nsp.nspname = 'asset'
          AND rel.relname = 'library_blobs'
          AND att.attname = 'media_type'
          AND con.contype = 'c'
        LOOP
            EXECUTE format('ALTER TABLE asset.library_blobs DROP CONSTRAINT %I', constraint_name);
        END LOOP;
END $$;

ALTER TABLE asset.library_blobs
    ADD CONSTRAINT library_blobs_media_type_check
        CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER'));
