-- 分镜参考素材区分用途：image=图片生成参考图 / video=视频生成参考素材（图片/视频界面参考图互不共享）
ALTER TABLE public.scene_reference_images ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'image';
