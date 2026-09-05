package com.moon.moonagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "storyboard.internal-secret=test-internal-secret")
class MoonAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
