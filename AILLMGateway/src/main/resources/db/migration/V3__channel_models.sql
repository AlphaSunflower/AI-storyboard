-- 渠道表增加模型列表：管理员维护该渠道支持的模型名（逗号分隔），用于测试弹窗候选；空值则仅用已配路由的模型
ALTER TABLE channel ADD COLUMN IF NOT EXISTS models TEXT;
