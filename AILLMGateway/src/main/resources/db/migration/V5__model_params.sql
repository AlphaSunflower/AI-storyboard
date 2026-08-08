-- 模型参数能力+默认值表（OpenAI 契约全 API：文本/图像/视频）
-- 一行一模型（model_name UNIQUE，模型级能力，同模型多渠道共享）
-- 枚举列用逗号分隔 TEXT（规避 MP+PG 数组坑）；默认值列 = 发送时默认携带
CREATE TABLE IF NOT EXISTS model_params (
    id VARCHAR(32) PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL DEFAULT 'text',
    -- text（chat/completions 默认值）
    temperature TEXT,
    max_tokens INT,
    top_p TEXT,
    -- image（images/generations + edits 共用）：能力 + 默认
    n_min INT,
    n_max INT,
    n_default INT,
    sizes TEXT,
    size_default TEXT,
    qualities TEXT,
    quality_default TEXT,
    styles TEXT,
    style_default TEXT,
    -- video（videos）：能力 + 默认
    durations TEXT,
    duration_default TEXT,
    resolutions TEXT,
    resolution_default TEXT,
    aspect_ratios TEXT,
    aspect_ratio_default TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
