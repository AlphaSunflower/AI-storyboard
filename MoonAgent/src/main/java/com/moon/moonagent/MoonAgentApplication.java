package com.moon.moonagent;

import com.moon.moonagent.service.ai.AiConfigProperties;
import com.moon.moonagent.service.ai.AgentAiConfigProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AiConfigProperties.class, AiConfigProperties.Gateway.class, AgentAiConfigProperties.class})
public class MoonAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MoonAgentApplication.class, args);
    }
}
