-- 账号收敛：只保留 admin@qq.com（由 771038325@qq.com 改名而来，保留 id 与全部数据）
-- 由 03-post-migrate.sh 以 psql -v admin_hash=... 调用
\set ON_ERROR_STOP on

BEGIN;

-- 1) 改名 + 提权 + 重置密码（密码哈希由 scripts/dump-db.ps1 生成，N=16384,r=8,p=1,keylen=64）
UPDATE public.users
   SET email = 'admin@qq.com',
       display_name = 'admin',
       role = 'admin',
       status = 'enabled',
       password_hash = :'admin_hash',
       updated_at = now()
 WHERE email = '771038325@qq.com';

-- 2) 删除其它用户的根数据（会话/项目），CASCADE 带走 agent/canvas/scene 明细
DELETE FROM public.conversations c
 WHERE c.user_id <> (SELECT id FROM public.users WHERE email = 'admin@qq.com');

DELETE FROM public.projects p
 WHERE p.user_id <> (SELECT id FROM public.users WHERE email = 'admin@qq.com');

-- 2b) 删除其它账号本身（users 表没有指向自己的外键，必须显式删）
DELETE FROM public.users u
 WHERE u.id <> (SELECT id FROM public.users WHERE email = 'admin@qq.com');

-- 3) 所有带 owner 语义列的说明表：清掉非 admin 的行
DO $$
DECLARE
  r record;
  kept text;
BEGIN
  SELECT id INTO kept FROM public.users WHERE email = 'admin@qq.com';
  FOR r IN
    SELECT c.table_schema, c.table_name, c.column_name
      FROM information_schema.columns c
     WHERE c.table_schema IN ('public', 'asset', 'agentscope')
       AND c.column_name IN ('user_id', 'owner_user_id', 'added_by_user_id', 'created_by_user_id')
  LOOP
    EXECUTE format('DELETE FROM %I.%I t WHERE t.%I IS NOT NULL AND t.%I <> $1',
                   r.table_schema, r.table_name, r.column_name, r.column_name)
      USING kept;
  END LOOP;
END $$;

-- 4) 通用孤儿清理：按外键反复清除悬挂子行（容忍 RESTRICT / NO ACTION 的暂态阻塞）
DO $$
DECLARE
  pass int := 0;
  deleted int := 0;
  n int := 0;
  r record;
BEGIN
  LOOP
    pass := pass + 1;
    deleted := 0;
    FOR r IN
      SELECT tc.table_schema AS ts, tc.table_name AS tn, kcu.column_name AS cn,
             ccu.table_schema AS rs, ccu.table_name AS rt, ccu.column_name AS rc
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
        JOIN information_schema.constraint_column_usage ccu
          ON ccu.constraint_name = tc.constraint_name
       WHERE tc.constraint_type = 'FOREIGN KEY'
         AND tc.table_schema IN ('public', 'asset')
    LOOP
      BEGIN
        EXECUTE format(
          'DELETE FROM %I.%I c WHERE c.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM %I.%I p WHERE p.%I = c.%I)',
          r.ts, r.tn, r.cn, r.rs, r.rt, r.rc, r.cn);
        GET DIAGNOSTICS n = ROW_COUNT;
        deleted := deleted + n;
      EXCEPTION WHEN foreign_key_violation THEN
        NULL;
      END;
    END LOOP;
    EXIT WHEN deleted = 0 OR pass >= 8;
  END LOOP;
END $$;

-- 5) 清空待处理队列，避免历史任务动到保留下来的 MinIO 对象
DO $$
BEGIN
  IF to_regclass('public.storage_cleanup_queue') IS NOT NULL THEN
    EXECUTE 'TRUNCATE public.storage_cleanup_queue';
  END IF;
  IF to_regclass('asset.library_storage_cleanup_tasks') IS NOT NULL THEN
    EXECUTE 'TRUNCATE asset.library_storage_cleanup_tasks';
  END IF;
END $$;

-- 5b) 清理不再被任何媒体引用的 blob 元数据（对象本体仍保留在 MinIO）
DO $$
BEGIN
  IF to_regclass('asset.library_blobs') IS NOT NULL THEN
    EXECUTE 'DELETE FROM asset.library_blobs b WHERE NOT EXISTS (SELECT 1 FROM asset.library_media m WHERE m.blob_id = b.id)';
  END IF;
END $$;

-- 6) AgentScope 会话状态按 userId 前缀清理（session_id = <userId>:<hash>）
DO $$
DECLARE
  kept text;
BEGIN
  SELECT id INTO kept FROM public.users WHERE email = 'admin@qq.com';
  IF to_regclass('public.agentscope_sessions') IS NOT NULL THEN
    EXECUTE 'DELETE FROM public.agentscope_sessions WHERE split_part(session_id, '':'', 1) <> $1' USING kept;
  END IF;
  IF to_regclass('agentscope.agentscope_sessions') IS NOT NULL THEN
    EXECUTE 'DELETE FROM agentscope.agentscope_sessions WHERE split_part(session_id, '':'', 1) <> $1' USING kept;
  END IF;
END $$;

COMMIT;

-- 7) 迁移结果自检
SELECT 'users' AS metric, count(*)::text AS value FROM public.users
UNION ALL SELECT 'projects', count(*)::text FROM public.projects
UNION ALL SELECT 'conversations', count(*)::text FROM public.conversations
UNION ALL SELECT 'agent_messages', count(*)::text FROM public.agent_messages
UNION ALL SELECT 'scenes', count(*)::text FROM public.scenes
UNION ALL SELECT 'asset.library_assets', count(*)::text FROM asset.library_assets;
