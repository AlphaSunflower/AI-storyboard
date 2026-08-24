-- V6: model_route 表新增 is_default 列（按 (model_name, channel_id) 粒度控制默认模型）
-- 执行：psql -h localhost -U postgres -d newworkflow -f V6__model_route_add_is_default.sql

-- 1. 加列
ALTER TABLE model_route ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT false;

-- 2. 迁移旧数据：model_params.is_default=true 的模型 → 对应路由取第一条设为默认（每类型仅一个）
WITH ranked AS (
    SELECT r.id, r.type,
           ROW_NUMBER() OVER (PARTITION BY r.type ORDER BY r.created_at) AS rn
    FROM model_route r
    JOIN model_params p ON r.model_name = p.model_name
    WHERE r.deleted = false AND p.is_default = true
)
UPDATE model_route SET is_default = true
FROM ranked
WHERE model_route.id = ranked.id AND ranked.rn = 1;
