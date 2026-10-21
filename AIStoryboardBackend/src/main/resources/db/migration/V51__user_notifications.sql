-- 站内通知（2026-10-20）
--
-- 通知先落库（必达），Web Push 只是额外的投递通道。
-- dedupe_key 唯一索引让同一事件的重复写入天然幂等（任务结束时可能被多次 publish）。
CREATE TABLE IF NOT EXISTS public.user_notifications (
    id          varchar(64)  PRIMARY KEY,
    user_id     varchar(64)  NOT NULL,
    type        varchar(32)  NOT NULL,
    title       varchar(200) NOT NULL,
    body        text,
    link_type   varchar(32),
    link_id     varchar(64),
    dedupe_key  varchar(200),
    read_at     timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_notifications_dedupe
    ON public.user_notifications (dedupe_key)
    WHERE dedupe_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_notifications_unread
    ON public.user_notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;
