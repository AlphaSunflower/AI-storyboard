-- 模型参数自描述 Schema（V8）：model_params 加 params_schema 列
-- 存每模型的参数能力+适配契约 JSON（request + response.sync/async），替代/补充固定列
-- 手动执行（项目无 Flyway，db/migration 为留档，需 psql 应用）
ALTER TABLE model_params ADD COLUMN IF NOT EXISTS params_schema TEXT;
