package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaValidationTest {

    @Test
    void acceptsPngMagicBytes() {
        byte[] png = new byte[16];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4e;
        png[3] = 0x47;

        assertThatCode(() -> MediaValidation.validateMagic("IMAGE", png)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutableDisguisedAsImage() {
        assertThatThrownBy(() -> MediaValidation.validateMagic("IMAGE", "MZ-not-an-image".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void classifiesMediaDocumentsAndOtherFiles() {
        assertThat(MediaValidation.mediaType("image/png", "a.png")).isEqualTo("IMAGE");
        assertThat(MediaValidation.mediaType("video/mp4", "a.mp4")).isEqualTo("VIDEO");
        assertThat(MediaValidation.mediaType("audio/mpeg", "a.mp3")).isEqualTo("AUDIO");
        assertThat(MediaValidation.mediaType("application/pdf", "plan.pdf")).isEqualTo("DOCUMENT");
        assertThat(MediaValidation.mediaType("", "notes.md")).isEqualTo("DOCUMENT");
        assertThat(MediaValidation.mediaType("application/octet-stream", "brief.docx")).isEqualTo("DOCUMENT");
        assertThat(MediaValidation.mediaType("application/zip", "bundle.zip")).isEqualTo("OTHER");
        assertThat(MediaValidation.mediaType("", "archive.weird")).isEqualTo("OTHER");
    }

    @Test
    void skipsMagicValidationForDocumentsAndOtherFiles() {
        assertThatCode(() -> MediaValidation.validateMagic("DOCUMENT", "MZ-not-a-document".getBytes()))
                .doesNotThrowAnyException();
        assertThatCode(() -> MediaValidation.validateMagic("OTHER", new byte[0]))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcesAttachmentSizeLimitForDocumentsAndOtherFiles() {
        assertThatCode(() -> MediaValidation.validateSize("DOCUMENT", MediaValidation.ATTACHMENT_MAX_BYTES))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> MediaValidation.validateSize("OTHER", MediaValidation.ATTACHMENT_MAX_BYTES + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("20MB");
    }

    @Test
    void resolvesDocumentAndOtherObjectKeyFolders() {
        assertThat(MediaValidation.mediaTypeFromObjectKey("documents/a.pdf")).isEqualTo("DOCUMENT");
        assertThat(MediaValidation.mediaTypeFromObjectKey("files/a.zip")).isEqualTo("OTHER");
    }

    @Test
    void keepsArbitraryButSafeExtensionsForUnknownFiles() {
        assertThat(MediaValidation.extension("", "archive.tar.gz")).isEqualTo("gz");
        assertThat(MediaValidation.extension("", "README")).isEqualTo("bin");
        assertThat(MediaValidation.extension("application/pdf", "plan.pdf")).isEqualTo("pdf");
        assertThat(MediaValidation.extension("", "evil.<script>")).isEqualTo("bin");
    }
}
