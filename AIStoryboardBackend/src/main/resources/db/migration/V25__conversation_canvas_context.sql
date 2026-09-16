-- 会话级画布编辑上下文：固定项目/画布，直到用户显式离开画布编辑模式。
ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS canvas_context TEXT;
