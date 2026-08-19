package com.storyboard;

import com.storyboard.config.JwtConfig;
import com.storyboard.service.ai.AiConfigProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtConfig.class, AiConfigProperties.class, AiConfigProperties.Gateway.class})
@MapperScan("com.storyboard.mapper")
@EnableScheduling
public class StoryboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoryboardApplication.class, args);
    }
}
