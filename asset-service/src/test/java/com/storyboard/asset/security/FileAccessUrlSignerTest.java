package com.storyboard.asset.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileAccessUrlSignerTest {

    private final FileAccessUrlSigner signer = new FileAccessUrlSigner("test-internal-secret", 900);

    @Test
    void signsAndRefreshesLegacyFilePath() {
        String signed = signer.signIfLocal("/api/files/images/a.png");

        assertThat(signed).startsWith("/api/files/images/a.png?token=");
        assertThat(signer.signIfLocal(signed))
                .startsWith("/api/files/images/a.png?token=");
    }

    @Test
    void leavesAlreadyManagedContentUrlsUntouched() {
        String url = "/api/library/media/m1/content?token=existing";

        assertThat(signer.signIfLocal(url)).isEqualTo(url);
    }
}
