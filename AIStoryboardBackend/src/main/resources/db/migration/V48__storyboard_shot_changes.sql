-- 分镜草稿改动日志（2026-10-17）
--
-- 背景：大纲行现在可以在审批前直接改（replaceDraft），但"谁改了哪一镜、从什么改成什么"
-- 只活在内存里：刷新即丢、换设备查不到、审批之后再想追溯也没有证据。
--
-- 粒度是字段级：一行 = 一个字段的一次变化（画面/地点/时间/角色/资产）。
-- source 区分 user（用户在卡片里改）、agent（智能体调工具改）、system（规范化）。
CREATE TABLE IF NOT EXISTS storyboard_shot_changes (
    id           VARCHAR(64)  PRIMARY KEY,
    run_id       VARCHAR(64)  NOT NULL,
    shot_id      VARCHAR(64)  NOT NULL,
    shot_ordinal INT          NOT NULL DEFAULT 0,
    kind         VARCHAR(16)  NOT NULL,
    source       VARCHAR(16)  NOT NULL,
    field        VARCHAR(32)  NOT NULL,
    before_value TEXT,
    after_value  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_storyboard_shot_changes_run
    ON storyboard_shot_changes (run_id, created_at DESC);

COMMENT ON TABLE storyboard_shot_changes IS '分镜草稿字段级改动日志（变更审阅数据源）';
