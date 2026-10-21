-- 账号级外观偏好：light / dark / system。
-- 默认 light：历史用户保持当前浅色界面；选择「跟随系统」后由前端监听系统外观。
ALTER TABLE public.user_settings
    ADD COLUMN IF NOT EXISTS theme_preference VARCHAR(16) NOT NULL DEFAULT 'light';

ALTER TABLE public.user_settings
    DROP CONSTRAINT IF EXISTS ck_user_settings_theme_preference;

ALTER TABLE public.user_settings
    ADD CONSTRAINT ck_user_settings_theme_preference
    CHECK (theme_preference IN ('light', 'dark', 'system'));
