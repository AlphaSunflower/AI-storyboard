-- 模型上下文窗口（token 数）：随 /v1/models 的 params.contextWindow 透出，
-- 供 MoonAgent 计算「上下文占用」分母（单一权威源下沉网关，替代后端硬编码模型表）。
-- 可空：空表示未配置，调用方回退自身默认窗口。
ALTER TABLE model_params ADD COLUMN IF NOT EXISTS context_window INT;

-- 种子：对齐当前已知窗口；后续由 admin 面板按模型调整。
-- DeepSeek / MiMo 全系按 1M。
UPDATE model_params SET context_window = 1000000
WHERE context_window IS NULL
  AND (model_name LIKE 'deepseek%' OR model_name LIKE 'mimo%');

UPDATE model_params SET context_window = 1000000
WHERE context_window IS NULL
  AND (model_name LIKE 'gemini-2.5%' OR model_name LIKE 'gemini-3%'
       OR model_name LIKE 'gemini-2.0%' OR model_name LIKE 'gpt-4.1%');

UPDATE model_params SET context_window = 200000
WHERE context_window IS NULL
  AND (model_name LIKE 'claude%' OR model_name LIKE 'kimi%'
       OR model_name LIKE 'o3%' OR model_name LIKE 'o4-mini%');

-- 显式补齐不易用前缀命名的行（如 orcarouter/... 、vanchin/... 等渠道前缀变体）。
UPDATE model_params SET context_window = 200000
WHERE context_window IS NULL
  AND (model_name LIKE '%deepseek%' OR model_name LIKE 'kimi%');

UPDATE model_params SET context_window = 128000
WHERE context_window IS NULL
  AND (model_name LIKE 'qwen%' OR model_name LIKE '%/qwen%');

UPDATE model_params SET context_window = 128000
WHERE context_window IS NULL
  AND (model_name LIKE 'gpt-4o%' OR model_name LIKE 'qwen3-max%');
