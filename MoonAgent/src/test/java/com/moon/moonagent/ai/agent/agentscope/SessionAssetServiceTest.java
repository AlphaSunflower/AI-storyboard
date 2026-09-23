package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionAssetServiceTest {

    @Test
    void resolvePrefersRecentGeneratedImageForReferentialRequest() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setAssetSearchLimit(8);
        AgentAsset image = asset("old", "image", "/old.png", "previous image",
                OffsetDateTime.now().minusMinutes(1));
        AgentAsset reference = asset("new", "reference", "/new.png", "uploaded reference",
                OffsetDateTime.now());
        when(mapper.selectList(any())).thenReturn(List.of(reference, image));

        SessionAssetService service = new SessionAssetService(mapper, properties);
        List<SessionAssetRef> result = service.resolve(
                "c1", null, "上一张图片", 2, true);

        assertThat(result).isNotEmpty();
        assertThat(result.getFirst().assetId()).isEqualTo("old");
        assertThat(result.getFirst().url()).isEqualTo("/old.png");
    }

    private static AgentAsset asset(String id, String type, String url, String prompt,
                                    OffsetDateTime createdAt) {
        AgentAsset asset = new AgentAsset();
        asset.setId(id);
        asset.setType(type);
        asset.setUrl(url);
        asset.setPrompt(prompt);
        asset.setStatus("completed");
        asset.setCreatedAt(createdAt);
        return asset;
    }
}
