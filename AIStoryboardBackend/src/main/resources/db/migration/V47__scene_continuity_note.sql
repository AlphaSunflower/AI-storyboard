-- 分镜衔接说明（2026-10-17）
--
-- 背景：跨镜一致性此前没有任何载体。PseudoShot.continuity 只有在伪分镜阶段才可能被填，
-- 写到 scenes/scene_prompts 后被整个丢掉，于是「上一镜的雨、湿发、衣服、光位要不要延续」
-- 全靠模型每镜自由发挥，结果就是同一角色在不同镜头里长得不一样。
--
-- 本列由分镜生成阶段产出（继承项 / 禁改项），生图与生视频组装提示词时拼接在末尾：
--   「[衔接] 承接上一镜的雨夜冷蓝侧逆光；人物湿发与外衣水痕不得回退」
ALTER TABLE scene_prompts ADD COLUMN IF NOT EXISTS continuity_note TEXT;

COMMENT ON COLUMN scene_prompts.continuity_note IS '本镜与前镜的衔接说明（继承项/禁改项），生成时拼进提示词';
