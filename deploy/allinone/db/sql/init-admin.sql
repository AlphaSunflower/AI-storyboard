-- 全量数据模式：只确保存在一个可登录的管理员账号，绝不删除任何用户/项目/会话/资产。
--
-- 由 03-init-admin.sh 以 psql -v admin_hash=... 调用。
--
-- 账号策略（按优先级）：
--   1) 已有 admin@qq.com           -> 重置为管理员 + 已知密码（保留该账号全部数据）
--   2) 只有 771038325@qq.com       -> 原地改名成 admin@qq.com（id 不变，项目/会话/资产全部跟随）
--   3) 两个都没有                   -> 新建 admin@qq.com
-- 其它 156 个账号、全部项目、会话、消息、资产、画布、MinIO 引用一律不动。
\set ON_ERROR_STOP on

BEGIN;

-- 1) 仅当 admin 不存在时才把主账号改名，避免邮箱唯一约束冲突
UPDATE public.users
   SET email          = 'admin@qq.com',
       display_name   = 'admin',
       role           = 'admin',
       status         = 'enabled',
       password_hash  = :'admin_hash',
       updated_at     = now()
 WHERE email = '771038325@qq.com'
   AND NOT EXISTS (SELECT 1 FROM public.users WHERE email = 'admin@qq.com');

-- 2) 已存在的 admin 重置为可登录状态（密码哈希由 admin-scrypt.txt 提供，对应 123456）
UPDATE public.users
   SET display_name  = 'admin',
       role          = 'admin',
       status        = 'enabled',
       password_hash = :'admin_hash',
       updated_at    = now()
 WHERE email = 'admin@qq.com';

-- 3) 两者都不存在时新建（正常全量转储不会走到这里）
INSERT INTO public.users (id, email, password_hash, display_name, role, status, created_at, updated_at)
SELECT gen_random_uuid()::text, 'admin@qq.com', :'admin_hash', 'admin', 'admin', 'enabled', now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM public.users WHERE email = 'admin@qq.com');

COMMIT;

-- 4) 自检：数据量必须与转储一致（这里只打印，不删除、不修改）
SELECT 'users' AS metric, count(*)::text AS value FROM public.users
UNION ALL SELECT 'projects', count(*)::text FROM public.projects
UNION ALL SELECT 'conversations', count(*)::text FROM public.conversations
UNION ALL SELECT 'agent_messages', count(*)::text FROM public.agent_messages
UNION ALL SELECT 'agent_assets', count(*)::text FROM public.agent_assets
UNION ALL SELECT 'scenes', count(*)::text FROM public.scenes;

SELECT email, display_name, role, status FROM public.users WHERE email = 'admin@qq.com';
