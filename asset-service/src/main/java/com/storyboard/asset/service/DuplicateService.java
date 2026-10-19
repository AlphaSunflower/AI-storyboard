package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.DuplicateGroup;
import com.storyboard.asset.client.AssetContracts.MergeDuplicatesRequest;
import com.storyboard.asset.client.AssetContracts.PageResponse;

/** 重复素材检测与合并：同一 sha256 被多个素材引用时，保留一份并迁移引用。 */
public interface DuplicateService {

    PageResponse<DuplicateGroup> list(String userId, String projectId, int page, int size);

    AssetSummary merge(String userId, MergeDuplicatesRequest request);
}
