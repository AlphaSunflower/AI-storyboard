-- 一次性清理：参数默认值 / 键值映射 / 响应映射统一到 model_params.params_schema。
-- 模型路由只保留渠道绑定、上游模型名、优先级/权重、重试和回退。
-- 存量 param_overrides/default_params 不再生效，也不在管理端展示。

UPDATE model_route
SET default_params = NULL
WHERE default_params IS NOT NULL;

UPDATE model_route
SET param_overrides = NULL
WHERE param_overrides IS NOT NULL;
