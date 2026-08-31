-- V12__agent_asset_generation_record.sql
-- 生成记录扩展：agent_assets 从「产出素材行」升级为「一次生成尝试记录」。
--   - gen_type：文生图 / 图生图 / 文生视频 / 参考生视频 / 多图生图 / 首帧生视频 / 首尾帧生视频
--   - params：生成参数 JSON 字符串（size/quality/n/mode/resolution/aspectRatio/duration/negativePrompt...）
--   - reference_urls：参考素材 JSON 字符串（[{type,url,role?}]，image/video/audio + source）
--   - result_urls：本次生成结果 URL 数组 JSON 字符串（多图场景 >1）
--   - duration_ms：从尝试开始到终态耗时（毫秒）
--   - scene_id：若本次生成落入真实分镜则记录 sceneId（可为空）
-- 存量行回填：无法反推参考素材，按 type 归类为文生图 / 文生视频，result_urls=[url]。

ALTER TABLE agent_assets
    ADD COLUMN IF NOT EXISTS gen_type       VARCHAR(32),
    ADD COLUMN IF NOT EXISTS params         TEXT,
    ADD COLUMN IF NOT EXISTS reference_urls TEXT,
    ADD COLUMN IF NOT EXISTS result_urls    TEXT,
    ADD COLUMN IF NOT EXISTS duration_ms    BIGINT,
    ADD COLUMN IF NOT EXISTS scene_id       VARCHAR(64);

-- 记录列表按 (status, gen_type, created_at) 与 created_at 倒序分页
CREATE INDEX IF NOT EXISTS idx_agent_assets_gen_status_created
    ON agent_assets(gen_type, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_assets_created
    ON agent_assets(created_at DESC);

-- 存量行回填：参考素材不可考 → 文生图 / 文生视频；result_urls=[url]
UPDATE agent_assets
SET gen_type    = CASE
                      WHEN type = 'image' THEN 'text_to_image'
                      WHEN type = 'video' THEN 'text_to_video'
                      ELSE NULL
                  END,
    result_urls = CASE WHEN url IS NOT NULL THEN json_build_array(url)::text ELSE NULL END
WHERE gen_type IS NULL;
