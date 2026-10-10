package com.moon.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MoonSandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoonSandboxApplication.class, args);
    }
}
