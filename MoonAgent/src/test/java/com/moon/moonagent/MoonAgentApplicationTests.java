package com.moon.moonagent;

import com.storyboard.asset.client.AssetClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "storyboard.internal-secret=test-internal-secret",
        "storyboard.storage.access-key=minioadmin",
        "storyboard.storage.secret-key=minioadmin123"
})
class MoonAgentApplicationTests {

	@Autowired
	private AssetClientProperties assetClientProperties;

	@Test
	void contextLoads() {
	}

	@Test
	void assetClientUsesSharedInternalSecretFallback() {
		assertThat(assetClientProperties.internalToken()).isEqualTo("test-internal-secret");
	}

}
