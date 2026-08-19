-- 模型默认标记：每个类型（text/image/video/vision）至多一个默认模型。
-- 后端启动时从 /v1/models 拉取 is_default=true 的模型作为兜底默认（单一权威源下沉网关）。
-- 部分唯一索引保证同类型唯一默认（仅 is_default=true 的行参与约束）。
ALTER TABLE model_params ADD COLUMN IF NOT EXISTS is_default BOOLEAN DEFAULT false;

-- 修正存量数据不一致：gemini-3-flash-preview 在 model_route 是 vision 类型，
-- 但 model_params 行是 text（建行时 type 默认值），对齐路由类型——否则「每类型默认」分组错位
UPDATE model_params SET type = 'vision' WHERE model_name = 'gemini-3-flash-preview' AND type = 'text';

-- 种子：与旧后端 config 默认值对齐（gpt-image-2 / MiniMax-H3 / gemini-3-flash-preview），
-- 保证部署后行为不回归；后续由 admin 面板在 model_params 表单里调整「默认模型」勾选。
UPDATE model_params SET is_default = true WHERE model_name = 'gpt-image-2' AND type = 'image';
UPDATE model_params SET is_default = true WHERE model_name = 'MiniMax-H3' AND type = 'video';
UPDATE model_params SET is_default = true WHERE model_name = 'gemini-3-flash-preview' AND type = 'vision';

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_params_default_per_type
    ON model_params (type) WHERE is_default = true;
