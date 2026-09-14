package com.storyboard.dto.request;

import java.util.List;
import java.util.Map;

/** 分镜保存时冻结的资产组快照；后续组变化不回写历史分镜。 */
public record SceneMaterialGroupSnapshotRequest(
        String snapshotId,
        String groupId,
        Long groupVersion,
        String contentHash,
        String target,
        List<Map<String, Object>> items
) {
}
