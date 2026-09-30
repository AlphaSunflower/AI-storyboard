package com.moon.xiaoyuecanvas.service;

import java.util.List;

public interface CanvasSummaryService {

    List<CanvasSummary> byProject(String projectId);

    ProjectSummary project(String projectId);

    record CanvasSummary(String canvasId, int nodeCount, int edgeCount, int assetCount) {
    }

    record ProjectSummary(int canvasCount, int nodeCount, int edgeCount, int assetCount) {
    }
}
