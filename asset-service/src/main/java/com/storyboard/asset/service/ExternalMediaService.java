package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.ExternalMediaRegistration;

/** 登记已经存在于共享对象存储中的媒体，不复制文件内容。 */
public interface ExternalMediaService {

    AssetSummary register(String actorUserId, ExternalMediaRegistration request);
}
