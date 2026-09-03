-- 模型参数改为按 (model_name, type) 唯一：同一物理模型可同时作为 text / vision 各持一套独立参数
-- （如理解模型开思考、文本模型关思考），互不影响。
-- 需先解除早期 V5 的 model_name 唯一约束，再建 (model_name, type) 唯一索引。
ALTER TABLE model_params DROP CONSTRAINT IF EXISTS model_params_model_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_model_params_name_type
    ON model_params (model_name, type);
