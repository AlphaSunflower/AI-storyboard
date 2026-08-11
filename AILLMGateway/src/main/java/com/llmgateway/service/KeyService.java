package com.llmgateway.service;

import com.llmgateway.dto.admin.ApiKeyRequest;
import com.llmgateway.entity.GatewayApiKey;

import java.util.List;
import java.util.Map;

/** 密钥服务：渠道 Key AES-256-GCM 加解密 + 业务 Key SHA-256 哈希比对 + 业务调用 Key 管理（admin 模块） */
public interface KeyService {

    /** AES-256-GCM 加密：IV 前置 + 密文 + tag，Base64 编码 */
    String encrypt(String plain);

    /** AES-256-GCM 解密（encrypt 的逆过程） */
    String decrypt(String cipherB64);

    /** SHA-256 哈希（业务调用 Key 存储用） */
    String sha256(String input);

    // ===== 管理后台 GatewayApiKey CRUD（admin 模块使用）=====

    /** 签发 Key：生成 lg-<32hex> 明文 → 存 sha256(明文) → 返回 {id,name,plainKey}（明文仅此一次） */
    Map<String, String> createKey(ApiKeyRequest request);

    /** Key 列表（按 createdAt 倒序，keyHash 已抹除） */
    List<GatewayApiKey> listKeys();

    /** enabled 开关更新（不存在抛 40401；keyHash 已抹除） */
    GatewayApiKey updateKey(String id, ApiKeyRequest request);

    /** 删除 Key（不存在抛 40401） */
    void deleteKey(String id);

    /** 业务调用 Key 总数 */
    long countAll();

    /** 启用中的业务调用 Key 数 */
    long countEnabled();
}
