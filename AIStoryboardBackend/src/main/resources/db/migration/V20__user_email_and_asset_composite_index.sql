-- V20__user_email_and_asset_composite_index.sql
-- D10: users.email 索引（登录/注册 findByEmail 高频路径，users 表由外部创建，补齐索引）
-- D11: agent_assets 复合索引（conversation_id + type + created_at DESC，覆盖 listAssets 分页查询）
BEGIN;

-- D10: users.email 唯一索引（登录 findByEmail 全表扫 → 点查）
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- D11: agent_assets 按 conversation_id + type 过滤 + created_at DESC 排序的最优索引
-- 现有 idx_agent_assets_conv (conversation_id, type) 不含 created_at，需 filesort；
-- idx_agent_assets_conv_created (conversation_id, created_at DESC) 不含 type，type 过滤需回表。
-- 三列复合索引一次覆盖：过滤 + 排序 + 分页。
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv_type_created
    ON agent_assets(conversation_id, type, created_at DESC);

COMMIT;
