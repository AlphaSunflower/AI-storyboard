-- V12__index_optimization.sql
-- 补齐/清理网关索引，对齐实际查询形状。
BEGIN;

-- model_route：路由查询按 model_name + 未删除条件过滤，此前无索引全表扫（最值回）。
-- 注意：路由优先级在 channel 表（priority 列），model_route 用 is_default（Boolean）标识默认，
-- 且不存在 priority 列，故只按 (model_name) 建部分索引。
CREATE INDEX IF NOT EXISTS idx_model_route_model
    ON model_route(model_name) WHERE deleted = false;

-- call_log：admin 日志分页按 model + created_at DESC；复合索引替代单独 model 索引。
CREATE INDEX IF NOT EXISTS idx_call_log_model_created
    ON call_log(model, created_at DESC);
DROP INDEX IF EXISTS idx_call_log_model;

-- channel：路由取 enabled=true 排序 priority。
CREATE INDEX IF NOT EXISTS idx_channel_enabled
    ON channel(enabled, priority);

COMMIT;
