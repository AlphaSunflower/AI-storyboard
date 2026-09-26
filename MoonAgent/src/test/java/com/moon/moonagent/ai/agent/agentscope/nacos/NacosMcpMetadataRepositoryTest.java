package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.ai.model.mcp.McpToolSpecification;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosMcpMetadataRepositoryTest {

    @Test
    void usesClasspathMetadataWhenNacosIsDisabled() {
        NacosAssetProperties properties = new NacosAssetProperties();
        NacosMcpMetadataRepository repository = repository(properties, () -> null);

        NacosMcpMetadataRepository.Snapshot snapshot = repository.snapshot();

        assertThat(snapshot.source()).isEqualTo("classpath");
        assertThat(snapshot.servers()).extracting(server -> server.get("name"))
                .containsExactly("llm-gateway-media");
        assertThat(repository.status())
                .containsEntry("source", "classpath")
                .containsKey("servers");
    }

    @Test
    void loadsRemoteMetadataFromNacos() throws Exception {
        NacosAssetProperties properties = new NacosAssetProperties();
        properties.setEnabled(true);
        AiService aiService = mock(AiService.class);
        McpServerDetailInfo detail = new McpServerDetailInfo();
        detail.setName("llm-gateway-media");
        ServerVersionDetail version = new ServerVersionDetail();
        version.setVersion("1.0.0");
        detail.setVersionDetail(version);
        McpTool tool = new McpTool();
        tool.setName("generate_image");
        tool.setInputSchema(Map.of("type", "object"));
        McpToolSpecification tools = new McpToolSpecification();
        tools.setTools(List.of(tool));
        detail.setToolSpec(tools);
        when(aiService.getMcpServer("llm-gateway-media", "1.0.0")).thenReturn(detail);

        NacosMcpMetadataRepository repository = repository(properties, () -> aiService);
        NacosMcpMetadataRepository.Snapshot snapshot = repository.snapshot();

        assertThat(snapshot.source()).isEqualTo("nacos");
        assertThat(snapshot.fallbackReason()).isNull();
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> remoteTools =
                (List<Map<?, ?>>) snapshot.servers().getFirst().get("tools");
        assertThat(remoteTools).hasSize(1);
        assertThat(remoteTools.getFirst().get("name")).isEqualTo("generate_image");
    }

    private static NacosMcpMetadataRepository repository(
            NacosAssetProperties properties,
            java.util.function.Supplier<AiService> supplier) {
        return new NacosMcpMetadataRepository(
                properties,
                supplier,
                new ClassPathResource("agent-assets/mcp-servers.json"),
                new ObjectMapper());
    }
}
