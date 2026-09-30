package com.moon.moonagent.security;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundUrlGuardTest {

    @Test
    void publicLiteralAddressIsAllowed() {
        assertDoesNotThrow(() -> OutboundUrlGuard.requireSafeHttpUrl(
                "https://93.184.216.34/image.png"));
    }

    @Test
    void loopbackAndPrivateAddressesAreRejected() {
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("http://127.0.0.1:8084/internal"));
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("http://10.0.0.5/internal"));
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("http://169.254.169.254/latest/meta-data"));
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("http://[::1]/internal"));
    }

    @Test
    void invalidSchemesAndHostsAreRejected() {
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("file:///etc/passwd"));
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("not a url"));
        assertThrows(BusinessException.class,
                () -> OutboundUrlGuard.requireSafeHttpUrl("https://localhost/image.png"));
    }

    @Test
    void addressClassifierRejectsNonPublicRanges() throws Exception {
        assertFalse(OutboundUrlGuard.isPublicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(OutboundUrlGuard.isPublicAddress(InetAddress.getByName("fc00::1")));
        assertTrue(OutboundUrlGuard.isPublicAddress(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    void mediaPolicyAllowsOnlyRelativeOrConfiguredOrigins() {
        List<String> allowed = List.of("http://localhost:8082", "https://cdn.example.com");
        assertDoesNotThrow(() -> OutboundUrlGuard.requireAllowedMediaUrl(
                "/api/files/images/a.png", allowed));
        assertDoesNotThrow(() -> OutboundUrlGuard.requireAllowedMediaUrl(
                "https://cdn.example.com/media/a.png", allowed));
        assertThrows(BusinessException.class, () -> OutboundUrlGuard.requireAllowedMediaUrl(
                "https://attacker.example/a.png", allowed));
    }
}
