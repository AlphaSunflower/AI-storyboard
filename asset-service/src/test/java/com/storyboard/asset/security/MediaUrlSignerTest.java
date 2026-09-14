package com.storyboard.asset.security;

import com.storyboard.asset.config.AssetProperties;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUrlSignerTest {

    private final MediaUrlSigner signer = new MediaUrlSigner(
            new AssetProperties("", "", "", "test-secret"));

    @Test
    void acceptsValidToken() {
        String token = signer.sign("m1", "u1", Instant.now().plusSeconds(60));

        signer.verify("m1", "u1", token);
    }

    @Test
    void rejectsTokenForDifferentUser() {
        String token = signer.sign("m1", "u1", Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> signer.verify("m1", "u2", token))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = signer.sign("m1", "u1", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> signer.verify("m1", "u1", token))
                .isInstanceOf(BusinessException.class);
    }
}
