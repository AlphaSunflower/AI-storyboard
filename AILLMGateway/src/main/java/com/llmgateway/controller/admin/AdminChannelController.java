package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.dto.vo.ChannelVO;
import com.llmgateway.entity.Channel;
import com.llmgateway.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 渠道管理：Key 写入 AES 加密，读取永远不返回明文 */
@RestController
@RequestMapping("/admin/channels")
@RequiredArgsConstructor
public class AdminChannelController {

    private final ChannelService channelService;

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

    private ChannelVO toVO(Channel e) {
        return new ChannelVO(e.getId(), e.getName(), e.getType(), e.getBaseUrl(), e.getModels(),
                e.getEnabled(), e.getPriority(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
