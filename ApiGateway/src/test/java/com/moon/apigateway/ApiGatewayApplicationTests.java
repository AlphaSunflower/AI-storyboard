package com.moon.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "jwt.access-secret=test-jwt-access-secret-0123456789abcdef")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
