package com.storyboard;

import com.storyboard.config.JwtConfig;
import com.storyboard.service.ai.AgentAiConfigProperties;
import com.storyboard.service.ai.AiConfigProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableConfigurationProperties({JwtConfig.class, AiConfigProperties.class, AiConfigProperties.Gateway.class, AgentAiConfigProperties.class})
@MapperScan("com.storyboard.mapper")
public class StoryboardApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(StoryboardApplication.class, args);
    }

    private static void loadDotEnv() {
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (System.getProperty(key) == null) {
                            System.setProperty(key, value);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }
}
