-- 账号级偏好设置：审批模式默认值、繁忙时发送行为、工作步骤展示级别。
CREATE TABLE IF NOT EXISTS public.user_settings (
    user_id               VARCHAR(64) PRIMARY KEY,
    default_approval_mode VARCHAR(32) NOT NULL DEFAULT 'PLAN_APPROVAL',
    busy_send_behavior    VARCHAR(16) NOT NULL DEFAULT 'steer',
    work_step_display     VARCHAR(16) NOT NULL DEFAULT 'detailed',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 帮助与反馈：正文 + 最多 6 张截图（只存站内相对路径，不存带签名的临时 URL）。
-- 不收集联系方式：处理进度由管理员在「反馈收件箱」里查看。
CREATE TABLE IF NOT EXISTS public.user_feedback (
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    content     TEXT NOT NULL,
    image_urls  TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'open',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 早期草稿曾带 contact 列；已确认不收集联系方式，存在则删掉。
ALTER TABLE public.user_feedback DROP COLUMN IF EXISTS contact;

CREATE INDEX IF NOT EXISTS idx_user_feedback_user_created
    ON public.user_feedback(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_feedback_status_created
    ON public.user_feedback(status, created_at DESC);
