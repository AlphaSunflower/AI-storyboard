package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

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
}
