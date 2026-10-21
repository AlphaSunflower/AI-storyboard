-- 回收站保留期 3 天 → 1 天：把仍可恢复的存量 TRASHED 资产同步收紧到期时间。
-- 只改「到期时间晚于进站时间 + 1 天」的行，已到期/已进入清理状态的行保持原样。
UPDATE asset.library_assets
SET purge_after = deleted_at + interval '1 day',
    updated_at  = now()
WHERE status = 'TRASHED'
  AND deleted_at IS NOT NULL
  AND purge_after IS NOT NULL
  AND purge_after > deleted_at + interval '1 day';
