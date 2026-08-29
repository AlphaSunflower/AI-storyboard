package com.moon.xiaoyuecanvas.controller;

import com.moon.xiaoyuecanvas.dto.request.CanvasGraphSaveRequest;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.service.CanvasGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Morrow 画布节点图契约端点（整幅 {nodes, edges, viewport} 快照 + revision 乐观锁）：
 * <ul>
 *   <li>GET /canvases/{id}/graph → {graph, revision, updatedAt}</li>
 *   <li>PUT /canvases/{id}/graph → 保存（body {graph, baseRevision}）；冲突 40901（message 携带当前 revision）</li>
 * </ul>
 * 归属校验由 {@link CanvasGraphService#save}/{@link CanvasGraphService#load} 内 {@code MorrowCanvasService.get} 完成。
 */
@RestController
@RequiredArgsConstructor
public class MorrowCanvasGraphController {

    private final CanvasGraphService canvasGraphService;

    @GetMapping("/canvases/{id}/graph")
    public CanvasGraphResponse get(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return canvasGraphService.load(userId, id);
    }

    @PutMapping("/canvases/{id}/graph")
    public CanvasGraphResponse put(@RequestAttribute("userId") String userId,
                                   @PathVariable String id,
                                   @RequestBody CanvasGraphSaveRequest request) {
        return canvasGraphService.save(userId, id, request.graph(), request.baseRevision());
    }
}
