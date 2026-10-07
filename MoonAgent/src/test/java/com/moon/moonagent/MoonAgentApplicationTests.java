package com.moon.moonagent;

import com.moon.moonagent.support.PostgresContainerTest;
import com.storyboard.asset.client.AssetClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MoonAgentApplicationTests extends PostgresContainerTest {

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
