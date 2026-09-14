package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.ProjectOwnership;

/** 项目归属校验端口；当前实现调用主后端内部只读接口。 */
public interface ProjectAccessPort {

    ProjectOwnership requireOwned(String userId, String projectId);
}
