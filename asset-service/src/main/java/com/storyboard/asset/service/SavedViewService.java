package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.LibrarySavedView;
import com.storyboard.asset.client.AssetContracts.SaveViewRequest;
import com.storyboard.asset.client.AssetContracts.UpdateSavedViewRequest;

import java.util.List;

/** 项目库保存视图：把当前筛选条件命名保存，下次一键恢复。 */
public interface SavedViewService {

    List<LibrarySavedView> list(String userId, String projectId);

    LibrarySavedView create(String userId, SaveViewRequest request);

    LibrarySavedView update(String userId, String viewId, UpdateSavedViewRequest request);

    void delete(String userId, String viewId);
}
