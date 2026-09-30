package com.storyboard.storage;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioStorageTest {

    @Test
    void initFailsFastWhenCredentialsAreMissing() {
        MinioStorage storage = new MinioStorage();
        ReflectionTestUtils.setField(storage, "endpoint", "http://localhost:9000");
        ReflectionTestUtils.setField(storage, "accessKey", "");
        ReflectionTestUtils.setField(storage, "secretKey", "");
        ReflectionTestUtils.setField(storage, "bucket", "storyboard");
        ReflectionTestUtils.setField(storage, "region", "us-east-1");

        assertThatThrownBy(storage::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORYBOARD_STORAGE_ACCESS_KEY");
    }
}
