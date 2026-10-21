package com.moon.xiaoyuecanvas.controller;

import com.moon.xiaoyuecanvas.dto.request.CanvasExecutionCreateRequest;
import com.moon.xiaoyuecanvas.dto.response.CanvasExecutionResponse;
import com.moon.xiaoyuecanvas.service.CanvasExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 画布执行批次端点（服务端 DAG 执行）：
 * <ul>
 *   <li>POST /canvases/{canvasId}/executions            → 创建并开始执行（串行）</li>
 *   <li>GET  /canvases/{canvasId}/executions            → 最近批次（刷新后恢复用）</li>
 *   <li>GET  /canvases/{canvasId}/executions/{id}       → 批次快照（轮询）</li>
 *   <li>POST /canvases/{canvasId}/executions/{id}/cancel → 取消批次（连带取消当前 job）</li>
 * </ul>
 * 全部 JWT 鉴权，userId 由 JwtAuthFilter 注入；批次按 userId + canvasId 双重归属校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/canvases/{canvasId}/executions")
public class CanvasExecutionController {

    private final CanvasExecutionService canvasExecutionService;

    @PostMapping
    public CanvasExecutionResponse create(@RequestAttribute("userId") String userId,
                                          @PathVariable String canvasId,
                                          @Valid @RequestBody CanvasExecutionCreateRequest request) {
        return canvasExecutionService.create(userId, canvasId, request);
    }

    @GetMapping
    public List<CanvasExecutionResponse> list(@RequestAttribute("userId") String userId,
                                              @PathVariable String canvasId) {
        return canvasExecutionService.list(userId, canvasId);
    }

    @GetMapping("/{executionId}")
    public CanvasExecutionResponse get(@RequestAttribute("userId") String userId,
                                       @PathVariable String canvasId,
                                       @PathVariable String executionId) {
        return canvasExecutionService.get(userId, canvasId, executionId);
    }

    @PostMapping("/{executionId}/cancel")
    public CanvasExecutionResponse cancel(@RequestAttribute("userId") String userId,
                                          @PathVariable String canvasId,
                                          @PathVariable String executionId) {
        return canvasExecutionService.cancel(userId, canvasId, executionId);
    }
}
