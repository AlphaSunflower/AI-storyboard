package com.storyboard.security;

import com.lambdaworks.crypto.SCrypt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;

@Service
public class ScryptPasswordService {

    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH = 64;
    private static final int SCRYPT_N = 16384;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;

    /** 兼容 Node.js 存储格式 scrypt:{salt_hex}:{key_hex} 和 Java 自产格式 */
    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        try {
            byte[] salt, expectedKey;
            if (storedHash.startsWith("scrypt:")) {
                String[] parts = storedHash.split(":");
                if (parts.length != 3) return false;
                salt = hexToBytes(parts[1]);
                expectedKey = hexToBytes(parts[2]);
            } else {
                // Spring or other format - unsupported for now
                return false;
            }
            byte[] derived = SCrypt.scrypt(password.getBytes("UTF-8"), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH);
            return MessageDigest.isEqual(derived, expectedKey);
        } catch (Exception e) {
            return false;
        }
    }

    public String hashPassword(String password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(salt);
        byte[] derived = SCrypt.scrypt(password.getBytes("UTF-8"), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH);
        return "scrypt:" + bytesToHex(salt) + ":" + bytesToHex(derived);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2)
            b[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return b;
    }
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
