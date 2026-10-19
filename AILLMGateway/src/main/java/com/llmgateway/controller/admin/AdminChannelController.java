package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.dto.vo.OllamaSyncResult;
import com.llmgateway.dto.vo.ChannelVO;
import com.llmgateway.entity.Channel;
import com.llmgateway.service.ChannelService;
import com.llmgateway.service.OllamaModelSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 渠道管理：Key 写入 AES 加密，读取永远不返回明文 */
@RestController
@RequestMapping("/admin/channels")
@RequiredArgsConstructor
public class AdminChannelController {

    private final ChannelService channelService;
    private final OllamaModelSyncService ollamaModelSyncService;

    @PostMapping
    public ApiResponse<ChannelVO> create(@RequestBody ChannelRequest request) {
        return ApiResponse.ok(toVO(channelService.create(request)));
    }

    @GetMapping
    public ApiResponse<List<ChannelVO>> list() {
        return ApiResponse.ok(channelService.list().stream().map(this::toVO).toList());
    }

    @PutMapping("/{id}")
    public ApiResponse<ChannelVO> update(@PathVariable String id, @RequestBody ChannelRequest request) {
        return ApiResponse.ok(toVO(channelService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        channelService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 同步 Ollama 渠道模型：GET /api/tags → 建 model_route + 更新 channel.models + 清模型缓存 */
    @PostMapping("/{id}/sync-models")
    public ApiResponse<OllamaSyncResult> syncModels(@PathVariable String id) {
        return ApiResponse.ok(ollamaModelSyncService.sync(id));
    }

    private ChannelVO toVO(Channel e) {
        return new ChannelVO(e.getId(), e.getName(), e.getType(), e.getBaseUrl(), e.getModels(),
                e.getEnabled(), e.getPriority(), e.getWeight(), e.getTimeoutMs(), e.getMaxRetries(),
                e.getLastError(), e.getLastCheckedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
