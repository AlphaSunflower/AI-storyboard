CREATE TABLE asset.library_folders (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    scope               VARCHAR(16) NOT NULL CHECK (scope IN ('PROJECT','GLOBAL')),
    owner_project_id    TEXT,
    parent_id           TEXT REFERENCES asset.library_folders(id) ON DELETE RESTRICT,
    name                TEXT NOT NULL,
    normalized_name     TEXT NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT library_folders_scope_binding_check CHECK (
        (scope = 'PROJECT' AND owner_project_id IS NOT NULL)
        OR (scope = 'GLOBAL' AND owner_project_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_library_folders_sibling_name
    ON asset.library_folders (
        owner_user_id,
        scope,
        COALESCE(owner_project_id, ''),
        COALESCE(parent_id, ''),
        normalized_name
    );
CREATE INDEX idx_library_folders_parent
    ON asset.library_folders(owner_user_id, scope, owner_project_id, parent_id, sort_order, name);

CREATE TABLE asset.library_asset_folders (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    owner_user_id       TEXT NOT NULL,
    scope               VARCHAR(16) NOT NULL CHECK (scope IN ('PROJECT','GLOBAL')),
    owner_project_id    TEXT,
    asset_id            TEXT NOT NULL REFERENCES asset.library_assets(id) ON DELETE CASCADE,
    folder_id           TEXT NOT NULL REFERENCES asset.library_folders(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT library_asset_folders_scope_binding_check CHECK (
        (scope = 'PROJECT' AND owner_project_id IS NOT NULL)
        OR (scope = 'GLOBAL' AND owner_project_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_library_asset_folders_asset_scope
    ON asset.library_asset_folders (
        owner_user_id,
        scope,
        COALESCE(owner_project_id, ''),
        asset_id
    );
CREATE INDEX idx_library_asset_folders_folder
    ON asset.library_asset_folders(folder_id, asset_id);

-- Backfill the folder tree from historical folder imports. The virtual "未归档"
-- branch is not persisted, so files without a resolvable path need no mapping.
CREATE TEMP TABLE library_backfill_paths AS
SELECT
    i.asset_id,
    j.owner_user_id,
    j.project_id,
    i.relative_path,
    regexp_split_to_array(replace(i.relative_path, '\\', '/'), '/') AS parts
FROM asset.library_import_items i
JOIN asset.library_import_jobs j ON j.id = i.job_id
WHERE i.asset_id IS NOT NULL
  AND j.project_id IS NOT NULL
  AND i.relative_path IS NOT NULL
  AND i.relative_path <> ''
  AND cardinality(regexp_split_to_array(replace(i.relative_path, '\\', '/'), '/')) > 1;

CREATE TEMP TABLE library_backfill_levels AS
SELECT
    p.asset_id,
    p.owner_user_id,
    p.project_id,
    p.parts,
    level AS depth,
    p.parts[level] AS folder_name,
    array_to_string(p.parts[1:level], '/') AS folder_path,
    CASE WHEN level = 1 THEN NULL ELSE array_to_string(p.parts[1:level - 1], '/') END AS parent_path
FROM library_backfill_paths p
CROSS JOIN LATERAL generate_series(1, cardinality(p.parts) - 1) AS level
WHERE btrim(p.parts[level]) <> ''
  AND p.parts[level] <> '.'
  AND p.parts[level] <> '..';

CREATE TEMP TABLE library_backfill_folder_ids (
    owner_user_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    folder_path TEXT NOT NULL,
    folder_id TEXT NOT NULL,
    PRIMARY KEY(owner_user_id, project_id, folder_path)
);

DO $$
DECLARE
    level_row RECORD;
    resolved_parent_id TEXT;
    resolved_folder_id TEXT;
BEGIN
    FOR level_row IN
        SELECT DISTINCT
            owner_user_id, project_id, depth, folder_name, folder_path, parent_path
        FROM library_backfill_levels
        ORDER BY depth, folder_path
    LOOP
        IF level_row.depth = 1 THEN
            resolved_parent_id := NULL;
        ELSE
            SELECT folder_id INTO resolved_parent_id
            FROM library_backfill_folder_ids
            WHERE owner_user_id = level_row.owner_user_id
              AND project_id = level_row.project_id
              AND folder_path = level_row.parent_path;
        END IF;

        SELECT id INTO resolved_folder_id
        FROM asset.library_folders
        WHERE owner_user_id = level_row.owner_user_id
          AND scope = 'PROJECT'
          AND owner_project_id = level_row.project_id
          AND parent_id IS NOT DISTINCT FROM resolved_parent_id
          AND normalized_name = lower(btrim(level_row.folder_name))
        LIMIT 1;

        IF resolved_folder_id IS NULL THEN
            INSERT INTO asset.library_folders(
                owner_user_id, scope, owner_project_id, parent_id, name, normalized_name
            ) VALUES (
                level_row.owner_user_id,
                'PROJECT',
                level_row.project_id,
                resolved_parent_id,
                btrim(level_row.folder_name),
                lower(btrim(level_row.folder_name))
            )
            RETURNING id INTO resolved_folder_id;
        END IF;

        INSERT INTO library_backfill_folder_ids(owner_user_id, project_id, folder_path, folder_id)
        VALUES (
            level_row.owner_user_id,
            level_row.project_id,
            level_row.folder_path,
            resolved_folder_id
        )
        ON CONFLICT (owner_user_id, project_id, folder_path)
        DO UPDATE SET folder_id = EXCLUDED.folder_id;
    END LOOP;
END $$;

WITH latest_import_asset AS (
    SELECT DISTINCT ON (i.asset_id)
        i.asset_id,
        j.owner_user_id,
        j.project_id,
        array_to_string(
            (regexp_split_to_array(replace(i.relative_path, '\\', '/'), '/'))[
                1:cardinality(regexp_split_to_array(replace(i.relative_path, '\\', '/'), '/')) - 1
            ],
            '/'
        ) AS leaf_path
    FROM asset.library_import_items i
    JOIN asset.library_import_jobs j ON j.id = i.job_id
    WHERE i.asset_id IS NOT NULL
      AND j.project_id IS NOT NULL
      AND i.relative_path IS NOT NULL
      AND i.relative_path <> ''
    ORDER BY i.asset_id, j.created_at DESC, i.created_at DESC
)
INSERT INTO asset.library_asset_folders(
    owner_user_id, scope, owner_project_id, asset_id, folder_id
)
SELECT
    source.owner_user_id,
    'PROJECT',
    source.project_id,
    source.asset_id,
    mapping.folder_id
FROM latest_import_asset source
JOIN library_backfill_folder_ids mapping
  ON mapping.owner_user_id = source.owner_user_id
 AND mapping.project_id = source.project_id
 AND mapping.folder_path = source.leaf_path
ON CONFLICT DO NOTHING;

DROP TABLE library_backfill_levels;
DROP TABLE library_backfill_paths;
DROP TABLE library_backfill_folder_ids;
