package com.storyboard.asset.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Spring Boot 自动装配 asset-client，消费方无需重复扫描或配置 Bean。 */
@AutoConfiguration
@EnableConfigurationProperties(AssetClientProperties.class)
public class AssetClientAutoConfiguration {

    @Bean
    public AssetClient assetClient(AssetClientProperties properties) {
        return AssetClient.create(properties);
    }
}
