package com.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 网关启动类：手动读 .env（SB4 与 spring-dotenv 不兼容），支持任意目录启动 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync  // CallLogService 异步落库需要
public class LLMGatewayApplication {

    public static void main(String[] args) throws IOException {
        loadDotEnv();
        SpringApplication.run(LLMGatewayApplication.class, args);
    }

    /** 读取 .env（KEY=VALUE 逐行），仅当系统属性未设置时注入；候选路径：LLM_GATEWAY_ENV_FILE → CWD → AILLMGateway/.env */
    private static void loadDotEnv() throws IOException {
        Path envFile = resolveEnvFile();
        if (envFile == null || !Files.exists(envFile)) return;
        Map<String, String> props = new HashMap<>();
        for (String line : Files.readAllLines(envFile)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            int idx = t.indexOf('=');
            props.put(t.substring(0, idx).trim(), t.substring(idx + 1).trim());
        }
        props.forEach((k, v) -> { if (System.getProperty(k) == null) System.setProperty(k, v); });
    }

    private static Path resolveEnvFile() {
        String explicit = System.getProperty("LLM_GATEWAY_ENV_FILE");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        if (Files.exists(Path.of(".env"))) return Path.of(".env");
        if (Files.exists(Path.of("AILLMGateway/.env"))) return Path.of("AILLMGateway/.env");
        return null;
    }
}
