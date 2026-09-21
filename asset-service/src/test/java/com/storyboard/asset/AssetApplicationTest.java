package com.storyboard.asset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class AssetApplicationTest {

    @Test
    void scansSharedExceptionHandlingPackage() {
        SpringBootApplication application = AssetApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application).isNotNull();
        assertThat(application.scanBasePackages()).contains("com.storyboard.common");
    }
}
