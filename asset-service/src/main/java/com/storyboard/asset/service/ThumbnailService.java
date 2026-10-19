package com.storyboard.asset.service;

import com.storyboard.asset.storage.StoredObject;

/** 图片缩略图：按签名路径读取原图、缩放后写入 MinIO 缓存。 */
public interface ThumbnailService {

    StoredObject open(String path, String token, int size);

    /** 读原图并更新素材主色；后台补齐历史素材时使用。 */
    void analyzeColor(String path);
}
