ALTER TABLE asset.library_import_jobs
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'ASSETS',
    ADD COLUMN group_id TEXT REFERENCES asset.library_assets(id);

ALTER TABLE asset.library_import_items
    ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_library_import_jobs_group ON asset.library_import_jobs(group_id);
