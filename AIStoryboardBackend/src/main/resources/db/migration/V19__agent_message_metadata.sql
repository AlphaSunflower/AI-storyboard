-- V19__agent_message_metadata.sql
-- Agent 消息增加 metadata JSONB（保留 HITL 卡片、reasoning、trace 等结构化元数据）：
-- 默认 '{}'，前端缺失字段仍可工作（兼容）。
ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
