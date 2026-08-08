package com.llmgateway.service;

import com.llmgateway.config.GatewayConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 密钥服务：渠道 Key AES-256-GCM 加解密 + 业务 Key SHA-256 哈希比对 */
@Component
public class KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final byte[] aesKey;

    public KeyService(GatewayConfig config) {
        this.aesKey = config.getAes().getSecret().getBytes(StandardCharsets.UTF_8);
        if (this.aesKey.length != 32) {
            throw new IllegalStateException("LLM_GATEWAY_AES_SECRET 必须恰好 32 字节（AES-256），当前 " + this.aesKey.length + " 字节");
        }
    }

    /** AES-256-GCM 加密：IV 前置 + 密文 + tag，Base64 编码 */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
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
    public String decrypt(String cipherB64) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherB64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES));
            return new String(cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败（渠道 Key 可能被不同密钥加密）: " + e.getMessage(), e);
        }
    }

    /** SHA-256 哈希（业务调用 Key 存储用） */
    public String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 失败: " + e.getMessage(), e);
        }
    }
}
