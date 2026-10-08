package com.storyboard.security;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUrlSignerTest {

    private final FileUrlSigner signer = new FileUrlSigner("test-file-signing-secret", 900);

    @Test
    void signsAndVerifiesExactPath() {
        String signed = signer.signPath("/api/files/images/a.png");
        String token = signed.substring(signed.indexOf("token=") + 6);

        signer.verifyPath("/api/files/images/a.png", token);
        assertThat(signed).startsWith("/api/files/images/a.png?token=");
    }

    @Test
    void rejectsTamperedPath() {
        String signed = signer.signPath("/api/files/images/a.png");
        String token = signed.substring(signed.indexOf("token=") + 6);

        assertThatThrownBy(() -> signer.verifyPath("/api/files/images/b.png", token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("签名无效");
    }

    @Test
    void signsOnlyLocalMediaPaths() {
        assertThat(signer.signIfLocal("https://cdn.example.com/a.png"))
                .isEqualTo("https://cdn.example.com/a.png");
        assertThat(signer.signIfLocal("data:image/png;base64,abc"))
                .isEqualTo("data:image/png;base64,abc");
        assertThat(signer.signIfLocal("/api/files/images/a.png"))
                .startsWith("/api/files/images/a.png?token=");
    }

    @Test
    void reSignsCommaSeparatedMediaPaths() {
        String signed = signer.signCommaSeparated(
                "/api/files/images/a.png,/api/files/images/b.png");

        assertThat(signed).contains("/api/files/images/a.png?token=");
        assertThat(signed).contains(",");
        assertThat(signed).contains("/api/files/images/b.png?token=");
    }
}
