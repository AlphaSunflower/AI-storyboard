package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.dto.admin.ApiKeyRequest;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.service.KeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 密钥服务实现：渠道 Key AES-256-GCM 加解密 + 业务 Key SHA-256 哈希比对 + 业务调用 Key 管理（admin 模块） */
@Service
@RequiredArgsConstructor
public class KeyServiceImpl implements KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final GatewayConfig config;
    private final GatewayApiKeyMapper apiKeyMapper;

    /** AES 密钥懒加载（构造期不校验，首次加解密时构建并校验 32 字节） */
    private volatile byte[] aesKey;

    private byte[] aesKey() {
        byte[] k = aesKey;
        if (k == null) {
            synchronized (this) {
                k = aesKey;
                if (k == null) {
                    k = config.getAes().getSecret().getBytes(StandardCharsets.UTF_8);
                    if (k.length != 32) {
                        throw new IllegalStateException("LLM_GATEWAY_AES_SECRET 必须恰好 32 字节（AES-256），当前 " + k.length + " 字节");
                    }
                    aesKey = k;
                }
            }
        }
        return k;
    }

    /** AES-256-GCM 加密：IV 前置 + 密文 + tag，Base64 编码 */
    @Override
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败: " + e.getMessage(), e);
        }
    }

    /** AES-256-GCM 解密（encrypt 的逆过程） */
    @Override
    public String decrypt(String cipherB64) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherB64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES));
            return new String(cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败（渠道 Key 可能被不同密钥加密）: " + e.getMessage(), e);
        }
    }

    /** SHA-256 哈希（业务调用 Key 存储用） */
    @Override
    public String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 失败: " + e.getMessage(), e);
        }
    }

    // ===== 管理后台 GatewayApiKey CRUD（admin 模块使用）=====

    /** 签发 Key：生成 lg-<32hex> 明文 → 存 sha256(明文) → 响应 {id,name,plainKey}（明文仅此一次） */
    @Override
    public Map<String, String> createKey(ApiKeyRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(40001, "name 不能为空");
        }
        String plainKey = "lg-" + UUID.randomUUID().toString().replace("-", "");
        GatewayApiKey record = new GatewayApiKey();
        record.setName(request.getName());
        record.setKeyHash(sha256(plainKey));
        record.setEnabled(true);
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        apiKeyMapper.insert(record);
        return Map.of("id", record.getId(), "name", record.getName(), "plainKey", plainKey);
    }

    /** Key 列表：只返回 id/name/enabled，永不返回 hash/明文 */
    @Override
    public List<GatewayApiKey> listKeys() {
        List<GatewayApiKey> keys = apiKeyMapper.selectList(new LambdaQueryWrapper<GatewayApiKey>()
                .orderByDesc(GatewayApiKey::getCreatedAt));
        keys.forEach(k -> k.setKeyHash(null));   // 抹掉哈希，保证不泄漏
        return keys;
    }

    /** enabled 开关 */
    @Override
    public GatewayApiKey updateKey(String id, ApiKeyRequest request) {
        GatewayApiKey key = apiKeyMapper.selectById(id);
        if (key == null) throw new BusinessException(40401, "Key 不存在");
        if (request.getEnabled() != null) key.setEnabled(request.getEnabled());
        key.setUpdatedAt(OffsetDateTime.now());
        apiKeyMapper.updateById(key);
        key.setKeyHash(null);
        return key;
    }

    @Override
    public void deleteKey(String id) {
        if (apiKeyMapper.deleteById(id) == 0) throw new BusinessException(40401, "Key 不存在");
    }

    @Override
    public long countAll() {
        Long count = apiKeyMapper.selectCount(null);
        return count == null ? 0 : count;
    }

    @Override
    public long countEnabled() {
        Long count = apiKeyMapper.selectCount(new LambdaQueryWrapper<GatewayApiKey>()
                .eq(GatewayApiKey::getEnabled, true));
        return count == null ? 0 : count;
    }
}
