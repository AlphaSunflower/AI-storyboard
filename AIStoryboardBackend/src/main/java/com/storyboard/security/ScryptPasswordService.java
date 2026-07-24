package com.storyboard.security;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Password hashing and verification service using the scrypt key derivation function.
 * Uses Bouncy Castle's SCrypt implementation with Node.js crypto.scrypt-compatible
 * parameters (N=16384, r=8, p=1, keylen=64).
 *
 * <p>Storage format: {@code scrypt:{salt_hex}:{derived_key_hex}}
 * where salt is 16 random bytes and the derived key is 64 bytes.</p>
 */
@Service
public class ScryptPasswordService {

    private static final String SCRYPT_PREFIX = "scrypt";
    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH = 64;
    private static final int SCRYPT_N = 16384;  // 2^14
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;

    /**
     * Verify a plaintext password against a stored scrypt hash.
     *
     * @param password   the plaintext password to verify
     * @param storedHash the stored hash in "scrypt:{salt_hex}:{key_hex}" format
     * @return true if the password matches the hash, false otherwise
     */
    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }

        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 3 || !SCRYPT_PREFIX.equals(parts[0])) {
                return false;
            }

            byte[] salt = hexToBytes(parts[1]);
            byte[] expectedKey = hexToBytes(parts[2]);

            byte[] derivedKey = SCrypt.generate(
                    password.getBytes("UTF-8"), salt,
                    SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
            );

            return MessageDigest.isEqual(derivedKey, expectedKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hash a password using scrypt with a random 16-byte salt.
     *
     * @param password the plaintext password to hash
     * @return the hash in "scrypt:{salt_hex}:{key_hex}" format
     * @throws Exception if the underlying SCrypt operation fails
     */
    public String hashPassword(String password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(salt);

        byte[] derived = SCrypt.generate(
                password.getBytes("UTF-8"), salt,
                SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
        );

        return SCRYPT_PREFIX + ":" + bytesToHex(salt) + ":" + bytesToHex(derived);
    }

    /**
     * Convert a hex string to a byte array.
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    /**
     * Convert a byte array to a lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
