package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeReferenceInputTest {

    @Test
    void classifiesPlainUploadedImagesAsUploadOnly() {
        OrchestrationRequest request = request(
                List.of("/api/files/images/uploaded-a.png"), List.of());

        assertThat(MoonAgentRuntime.classifyReferenceInput(request, false))
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.UPLOAD_ONLY);
    }

    @Test
    void classifiesAssetContentUrlsAndSelectedAssetsAsAssetOnly() {
        OrchestrationRequest request = request(
                List.of("/api/library/media/media-a/content"), List.of("asset-a"));

        assertThat(MoonAgentRuntime.classifyReferenceInput(request, false))
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.ASSET_ONLY);
    }

    @Test
    void classifiesBoundGroupsAlongsideUploadedImagesAsMixed() {
        OrchestrationRequest request = request(
                List.of("/api/files/images/uploaded-a.png"), List.of());

        assertThat(MoonAgentRuntime.classifyReferenceInput(request, true))
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.MIXED);
    }

    @Test
    void classifiesAssetUrlAndUploadedImageAsMixed() {
        OrchestrationRequest request = request(
                List.of(
                        "/api/library/media/media-a/content",
                        "/api/files/images/uploaded-a.png"),
                List.of());

        assertThat(MoonAgentRuntime.classifyReferenceInput(request, false))
                .isEqualTo(AgentScopeTurnState.ReferenceInputMode.MIXED);
    }

    private static OrchestrationRequest request(List<String> picUrls, List<String> assetIds) {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "生成图片", picUrls, null);
        request.setAssetIds(assetIds);
        return request;
    }
}
