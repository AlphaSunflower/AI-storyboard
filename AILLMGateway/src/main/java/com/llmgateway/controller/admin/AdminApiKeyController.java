package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ApiKeyRequest;
import com.llmgateway.dto.vo.GatewayApiKeyVO;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.service.KeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 业务调用 Key 管理：签发时明文仅返回一次，库中只存 SHA-256 哈希 */
@RestController
@RequestMapping("/admin/api-keys")
@RequiredArgsConstructor
public class AdminApiKeyController {

    private final KeyService keyService;

    /** 签发 Key：生成 lg-<32hex> 明文 → 存 sha256(明文) → 响应 {id,name,plainKey}（明文仅此一次） */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody ApiKeyRequest request) {
        return ApiResponse.ok(keyService.createKey(request));
    }

    /** Key 列表：只返回 id/name/enabled，永不返回 hash/明文 */
    @GetMapping
    public ApiResponse<List<GatewayApiKeyVO>> list() {
        return ApiResponse.ok(keyService.listKeys().stream().map(this::toVO).toList());
    }

    /** enabled 开关 */
    @PutMapping("/{id}")
    public ApiResponse<GatewayApiKeyVO> update(@PathVariable String id, @RequestBody ApiKeyRequest request) {
        return ApiResponse.ok(toVO(keyService.updateKey(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        keyService.deleteKey(id);
        return ApiResponse.ok(null);
    }

    private GatewayApiKeyVO toVO(GatewayApiKey e) {
        return new GatewayApiKeyVO(e.getId(), e.getName(), e.getEnabled(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
