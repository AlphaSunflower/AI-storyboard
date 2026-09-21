package com.storyboard.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.storyboard.asset", "com.storyboard.common"})
@EnableScheduling
@EnableConfigurationProperties
@ConfigurationPropertiesScan
public class AssetApplication {

    static void main(String[] args) {
        SpringApplication.run(AssetApplication.class, args);
    }
}
