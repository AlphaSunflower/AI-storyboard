-- Ollama 兼容渠道：模型参数表增加本地/Ollama 扩展参数（JSON），
-- 存储思考开关/等级、num_ctx、seed、stop、keep_alive、format 等 openai_compatible 没有的字段。
-- 供 OllamaRouteAdapter 转发时作为兜底默认（客户端显式值优先），并经 /v1/models params.ollama 透出。
ALTER TABLE model_params ADD COLUMN IF NOT EXISTS ollama_params TEXT;
