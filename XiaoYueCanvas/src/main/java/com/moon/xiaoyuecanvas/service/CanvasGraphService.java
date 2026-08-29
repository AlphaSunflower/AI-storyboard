package com.moon.xiaoyuecanvas.service;

import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;

import java.util.Map;

/** 画布节点图服务：整幅 {nodes, edges, viewport} 快照读写 + revision 乐观锁。 */
public interface CanvasGraphService {

    /** 读画布图：无记录返回空图（revision 0）。属主校验（通过 project.userId）。 */
    CanvasGraphResponse load(String userId, String canvasId);

    /** 存画布图：乐观锁 CAS（baseRevision 不匹配抛 40901，message 携带当前 revision）。 */
    CanvasGraphResponse save(String userId, String canvasId, Map<String, Object> graph, Integer baseRevision);
}
