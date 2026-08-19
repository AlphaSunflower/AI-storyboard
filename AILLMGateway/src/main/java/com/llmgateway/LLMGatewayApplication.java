package com.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** 网关启动类：配置由 Spring Profile application-{profile}.yml 提供（不再读 .env） */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync  // CallLogService 异步落库需要
public class LLMGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LLMGatewayApplication.class, args);
    }
}
