package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ApiKeyRequest;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.service.KeyService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 业务调用 Key 管理：签发时明文仅返回一次，库中只存 SHA-256 哈希 */
@RestController
@RequestMapping("/admin/api-keys")
public class AdminApiKeyController {

    private final GatewayApiKeyMapper apiKeyMapper;
    private final KeyService keyService;

    public AdminApiKeyController(GatewayApiKeyMapper apiKeyMapper, KeyService keyService) {
        this.apiKeyMapper = apiKeyMapper;
        this.keyService = keyService;
    }

    /** 签发 Key：生成 lg-<32hex> 明文 → 存 sha256(明文) → 响应 {id,name,plainKey}（明文仅此一次） */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody ApiKeyRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(40001, "name 不能为空");
        }
        String plainKey = "lg-" + UUID.randomUUID().toString().replace("-", "");
        GatewayApiKey record = new GatewayApiKey();
        record.setName(request.getName());
        record.setKeyHash(keyService.sha256(plainKey));
        record.setEnabled(true);
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        apiKeyMapper.insert(record);
        return ApiResponse.ok(Map.of("id", record.getId(), "name", record.getName(), "plainKey", plainKey));
    }

    /** Key 列表：只返回 id/name/enabled，永不返回 hash/明文 */
    @GetMapping
    public ApiResponse<List<GatewayApiKey>> list() {
        List<GatewayApiKey> keys = apiKeyMapper.selectList(new LambdaQueryWrapper<GatewayApiKey>()
                .orderByDesc(GatewayApiKey::getCreatedAt));
        keys.forEach(k -> k.setKeyHash(null));   // 抹掉哈希，保证不泄漏
        return ApiResponse.ok(keys);
    }

    /** enabled 开关 */
    @PutMapping("/{id}")
    public ApiResponse<GatewayApiKey> update(@PathVariable String id, @RequestBody ApiKeyRequest request) {
        GatewayApiKey key = apiKeyMapper.selectById(id);
        if (key == null) throw new BusinessException(40401, "Key 不存在");
        if (request.getEnabled() != null) key.setEnabled(request.getEnabled());
        key.setUpdatedAt(OffsetDateTime.now());
        apiKeyMapper.updateById(key);
        key.setKeyHash(null);
        return ApiResponse.ok(key);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        if (apiKeyMapper.deleteById(id) == 0) throw new BusinessException(40401, "Key 不存在");
        return ApiResponse.ok(null);
    }
}
