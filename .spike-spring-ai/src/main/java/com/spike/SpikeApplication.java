package com.spike;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI 2.0.0 spike 启动类：验证 SB4 + AI2.0 上下文可启动、ChatClient 自动装配成功。
 * 用假 key 只验证装配，不发起真实模型调用（调用需要真实网关）。
 */
@SpringBootApplication
public class SpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpikeApplication.class, args);
    }

    @Bean
    ApplicationRunner verify(ChatClient.Builder builder) {
        return args -> {
            ChatClient client = builder.defaultSystem("test").build();
            System.out.println("[SPIKE] ChatClient auto-config OK: " + client.getClass().getName());
            System.out.println("[SPIKE] ChatClient.Builder OK: " + builder.getClass().getName());
        };
    }
}
