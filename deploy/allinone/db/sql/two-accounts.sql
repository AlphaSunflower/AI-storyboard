-- 账号收敛为「恰好两个」：admin@qq.com（管理员） + 第二个业务账号
-- 由 05-two-accounts.sh 以 psql -v 传入参数执行；在 03-post-migrate.sh 之后运行。
-- 对运行中的库也可重复执行（幂等）：
--   docker exec -i ai-lite-postgres psql -U postgres -d ai_storyboard \
--     -v admin_hash="$(cat db/admin-scrypt.txt)" -v second_hash="$(cat db/admin-scrypt.txt)" \
--     -v second_email=test@qq.com -v second_name=测试用户 -f - < db/sql/two-accounts.sql
\set ON_ERROR_STOP on

-- 0) 若 03 未执行（历史库仍叫 771038325@qq.com），先做一次等价收敛
UPDATE public.users
   SET email = 'admin@qq.com', display_name = 'admin', role = 'admin', status = 'enabled',
       password_hash = :'admin_hash', updated_at = now()
 WHERE email = '771038325@qq.com'
   AND NOT EXISTS (SELECT 1 FROM public.users WHERE email = 'admin@qq.com');

BEGIN;

-- 1) 第二个账号已存在则重置密码/显示名，不存在则新建（id 用 gen_random_uuid()::text 与表默认一致）
UPDATE public.users
   SET password_hash = :'second_hash',
       display_name  = :'second_name',
       role          = 'member',
       status        = 'enabled',
       updated_at    = now()
 WHERE email = :'second_email';

INSERT INTO public.users (id, email, password_hash, display_name, role, status)
SELECT (gen_random_uuid())::text, :'second_email', :'second_hash', :'second_name', 'member', 'enabled'
 WHERE NOT EXISTS (SELECT 1 FROM public.users WHERE email = :'second_email');

-- 2) 兜底：除这两个账号外的任何账号连同其会话/项目根数据一并清掉
DELETE FROM public.conversations
 WHERE user_id NOT IN (SELECT id FROM public.users WHERE email IN ('admin@qq.com', :'second_email'));

DELETE FROM public.projects
 WHERE user_id NOT IN (SELECT id FROM public.users WHERE email IN ('admin@qq.com', :'second_email'));

DELETE FROM public.users
 WHERE email NOT IN ('admin@qq.com', :'second_email');

COMMIT;

-- 3) 自检
SELECT 'accounts_total' AS metric, count(*)::text AS value FROM public.users
UNION ALL SELECT 'accounts_admin', count(*)::text FROM public.users WHERE email = 'admin@qq.com'
UNION ALL SELECT 'accounts_second', count(*)::text FROM public.users WHERE email = :'second_email';

SELECT email, display_name, role, status FROM public.users ORDER BY email;
