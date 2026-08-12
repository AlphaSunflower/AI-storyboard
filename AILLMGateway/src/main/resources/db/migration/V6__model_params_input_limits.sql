-- 视频模型输入约束（全部 nullable，兼容存量行；范围类拆 min/max 列）
ALTER TABLE model_params
    ADD COLUMN ref_images_min INT,
    ADD COLUMN ref_images_max INT,
    ADD COLUMN ref_videos_min INT,
    ADD COLUMN ref_videos_max INT,
    ADD COLUMN audio_count_min INT,
    ADD COLUMN audio_count_max INT,
    ADD COLUMN audio_segment_duration_min INT,
    ADD COLUMN audio_segment_duration_max INT,
    ADD COLUMN video_segment_duration_min INT,
    ADD COLUMN video_segment_duration_max INT,
    ADD COLUMN max_total_duration INT,
    ADD COLUMN max_total_files INT,
    ADD COLUMN max_video_size_mb INT,
    ADD COLUMN max_image_size_mb INT,
    ADD COLUMN max_audio_size_mb INT,
    ADD COLUMN max_request_body_mb INT,
    ADD COLUMN max_prompt_chars INT;
