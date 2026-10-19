package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ChannelKeyRequest;
import com.llmgateway.dto.vo.ChannelKeyVO;
import com.llmgateway.entity.ChannelKey;
import com.llmgateway.service.ChannelKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 渠道多 Key 池管理（Key 绝不回显明文/密文） */
@RestController
@RequestMapping("/admin/channels/{channelId}/keys")
@RequiredArgsConstructor
public class AdminChannelKeyController {

    private final ChannelKeyService keyService;

    @GetMapping
    public ApiResponse<List<ChannelKeyVO>> list(@PathVariable String channelId) {
        return ApiResponse.ok(keyService.list(channelId).stream().map(this::toVO).toList());
    }

    @PostMapping
    public ApiResponse<ChannelKeyVO> create(@PathVariable String channelId,
                                            @RequestBody ChannelKeyRequest request) {
        return ApiResponse.ok(toVO(keyService.create(channelId, request)));
    }

    @PutMapping("/{keyId}")
    public ApiResponse<ChannelKeyVO> update(@PathVariable String channelId,
                                            @PathVariable String keyId,
                                            @RequestBody ChannelKeyRequest request) {
        return ApiResponse.ok(toVO(keyService.update(channelId, keyId, request)));
    }

    @DeleteMapping("/{keyId}")
    public ApiResponse<Void> delete(@PathVariable String channelId, @PathVariable String keyId) {
        keyService.delete(channelId, keyId);
        return ApiResponse.ok(null);
    }

    private ChannelKeyVO toVO(ChannelKey key) {
        return new ChannelKeyVO(key.getId(), key.getChannelId(), key.getLabel(),
                key.getApiKey() == null || key.getApiKey().isBlank() ? "（空）" : "••••••",
                key.getWeight(), key.getEnabled(), key.getFailCount(), key.getCooldownUntil(),
                key.getLastUsedAt(), key.getCreatedAt(), key.getUpdatedAt());
    }
}
