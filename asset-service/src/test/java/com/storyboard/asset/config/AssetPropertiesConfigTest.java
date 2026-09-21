package com.storyboard.asset.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

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
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        AssetProperties properties = Binder.get(environment)
                .bind("storyboard.asset", AssetProperties.class)
                .orElseThrow(() -> new IllegalStateException("asset properties did not bind"));

        assertThat(properties.gatewayApiKey()).isEqualTo("lg-test");
    }
}
