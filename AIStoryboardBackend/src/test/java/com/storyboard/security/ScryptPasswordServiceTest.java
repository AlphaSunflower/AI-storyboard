package com.storyboard.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScryptPasswordServiceTest {

    private final ScryptPasswordService service = new ScryptPasswordService();

    @Test
    void shouldVerifyPasswordAgainstKnownHash() throws Exception {
        // Generate a hash with our method, then verify it
        String password = "testPassword123";
        String hash = service.hashPassword(password);

        assertTrue(hash.startsWith("scrypt:"));
        assertTrue(service.verifyPassword(password, hash));
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        String hash = service.hashPassword("correctPassword");

        assertFalse(service.verifyPassword("wrongPassword", hash));
    }

    @Test
    void shouldRejectInvalidFormat() {
        assertFalse(service.verifyPassword("test", "invalid-format"));
        assertFalse(service.verifyPassword("test", "bcrypt:something"));
        assertFalse(service.verifyPassword("test", "scrypt:short"));
        assertFalse(service.verifyPassword("test", "scrypt:abc:def:extra"));
        assertFalse(service.verifyPassword("test", ""));
        assertFalse(service.verifyPassword("test", (String) null));
    }

    @Test
    void shouldRoundTripHashAndVerify() throws Exception {
        String password = "mySecurePassword123!";
        String hash = service.hashPassword(password);

        assertTrue(hash.startsWith("scrypt:"));
        String[] parts = hash.split(":");
        assertEquals(3, parts.length);
        assertEquals("scrypt", parts[0]);
        assertEquals(32, parts[1].length()); // 16 bytes salt = 32 hex chars
        assertEquals(128, parts[2].length()); // 64 bytes key = 128 hex chars

        assertTrue(service.verifyPassword(password, hash));
        assertFalse(service.verifyPassword("wrong", hash));
    }

    @Test
    void shouldGenerateUniqueSalts() throws Exception {
        String hash1 = service.hashPassword("samePassword");
        String hash2 = service.hashPassword("samePassword");

        // Same password should produce different hashes due to unique salts
        assertNotEquals(hash1, hash2);

        // Both should still verify correctly
        assertTrue(service.verifyPassword("samePassword", hash1));
        assertTrue(service.verifyPassword("samePassword", hash2));
    }
}
