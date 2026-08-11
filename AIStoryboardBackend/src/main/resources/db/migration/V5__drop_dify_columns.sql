-- V5__drop_dify_columns.sql
-- AI Agent 脱离 Dify：删除 Dify 会话/消息 ID 残留列
-- （编排迁移后无代码引用，先删代码再删列；旧会话 dify 值废弃不影响 agent_messages 历史）

ALTER TABLE conversations DROP COLUMN IF EXISTS dify_conversation_id;
ALTER TABLE agent_messages DROP COLUMN IF EXISTS dify_message_id;
