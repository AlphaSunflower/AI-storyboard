package com.storyboard.asset.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPropertiesConfigTest {

    @Test
    void gatewayApiKeyFallsBackToLegacyAiGatewayProperty() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test-overrides",
                Map.of("ai.gateway.api-key", "lg-test")
        ));
        loadApplicationYaml(environment);

        AssetProperties properties = Binder.get(environment)
                .bind("storyboard.asset", AssetProperties.class)
                .orElseThrow(() -> new IllegalStateException("asset properties did not bind"));

        assertThat(properties.gatewayApiKey()).isEqualTo("lg-test");
    }

    @Test
    void storageDefaultsToOneDayTrashRetentionAndDisabledOrphanScan() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        loadApplicationYaml(environment);

        StorageProperties properties = Binder.get(environment)
                .bind("storyboard.storage", StorageProperties.class)
                .orElseThrow(() -> new IllegalStateException("storage properties did not bind"));

        assertThat(properties.trashRetentionDays()).isEqualTo(1);
        assertThat(properties.orphanScanEnabled()).isFalse();
        assertThat(properties.orphanScanIntervalHours()).isEqualTo(6);
        assertThat(properties.orphanScanGraceHours()).isEqualTo(24);
    }

    @Test
    void storageBindsExplicitOverrides() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("storyboard.storage.trash-retention-days", "5");
        overrides.put("storyboard.storage.orphan-scan-enabled", "true");
        overrides.put("storyboard.storage.orphan-scan-interval-hours", "2");
        overrides.put("storyboard.storage.orphan-scan-grace-hours", "12");
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        loadApplicationYaml(environment);

        StorageProperties properties = Binder.get(environment)
                .bind("storyboard.storage", StorageProperties.class)
                .orElseThrow(() -> new IllegalStateException("storage properties did not bind"));

        assertThat(properties.trashRetentionDays()).isEqualTo(5);
        assertThat(properties.orphanScanEnabled()).isTrue();
        assertThat(properties.orphanScanIntervalHours()).isEqualTo(2);
        assertThat(properties.orphanScanGraceHours()).isEqualTo(12);
    }

    private static void loadApplicationYaml(StandardEnvironment environment) throws Exception {
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
    }
}