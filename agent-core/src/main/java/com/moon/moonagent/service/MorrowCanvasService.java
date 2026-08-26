package com.moon.moonagent.service;

import com.moon.moonagent.entity.MorrowCanvas;

import java.util.List;

/**
 * Morrow 画布元数据服务——画布 id/title/project_id 落库，节点实体内容仍留浏览器 IndexedDB（按画布 id）。
 */
public interface MorrowCanvasService {

    /** 在项目下建画布（元数据），返回带 id 的画布实体。 */
    MorrowCanvas create(String userId, String projectId, String title);

    /** 项目下画布列表（updated_at 倒序）。 */
    List<MorrowCanvas> listByProject(String userId, String projectId);

    /** 取单个画布（属主校验：通过 project.userId）。 */
    MorrowCanvas get(String userId, String canvasId);

    /** 改画布标题（属主校验）。 */
    MorrowCanvas rename(String userId, String canvasId, String title);

    /** 删画布（属主校验）。 */
    void delete(String userId, String canvasId);
}
