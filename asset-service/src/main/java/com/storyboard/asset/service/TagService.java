package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssignTagsRequest;
import com.storyboard.asset.client.AssetContracts.CreateTagRequest;
import com.storyboard.asset.client.AssetContracts.TagRef;

import java.util.List;

public interface TagService {

    List<TagRef> list(String userId);

    TagRef create(CreateTagRequest request);

    void replaceAssetTags(String actorUserId, String assetId, AssignTagsRequest request);
}
