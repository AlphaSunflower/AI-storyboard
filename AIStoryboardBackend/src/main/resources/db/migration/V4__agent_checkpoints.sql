-- V4__agent_checkpoints.sql
-- AI Agent HITL checkpoint：人工确认暂停点（替代原 Dify form 快照内存 Map）
-- form_token 一次性消费（status pending→used），30 分钟过期对齐原 FORM_SNAPSHOT_TTL_MS

CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    action          TEXT NOT NULL,               -- agree | disagree | generate_image | generate_video | refine | done
    form_token      TEXT NOT NULL,               -- resume token（一次性，前端传 formToken/planToken）
    plan            TEXT,                       -- 方案快照 JSON 文本（分镜 items/图片方案/视频方案；TEXT 而非 JSONB——MyBatis-Plus 实体 String 直插免类型转换，解析在应用层）
    step            TEXT,                        -- 编排状态名（SCRIPT_OPTIMIZE/STORYBOARD_PLAN/... 便于恢复）
    status          TEXT NOT NULL DEFAULT 'pending',  -- pending | used | expired
    expiration_time TIMESTAMPTZ NOT NULL,        -- 创建时间 + 30min
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_conv ON agent_checkpoints(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_token ON agent_checkpoints(form_token);
