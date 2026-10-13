package com.moon.xiaoyuecanvas.service;

import java.util.List;

public interface CanvasSummaryService {

    List<CanvasSummary> byProject(String projectId);

    ProjectSummary project(String projectId);

    /** coverAssetId：该画布内最早放入的图片资产，用于项目页画布列表封面。 */
    record CanvasSummary(String canvasId, int nodeCount, int edgeCount, int assetCount, String coverAssetId) {
    }

    record ProjectSummary(int canvasCount, int nodeCount, int edgeCount, int assetCount) {
    }
}
