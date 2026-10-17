package com.llmgateway.service;

import com.llmgateway.dto.vo.OllamaSyncResult;
import com.llmgateway.entity.Channel;
import com.llmgateway.mapper.ChannelMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OllamaModelSyncServiceTest {

    private final ChannelMapper channelMapper = mock(ChannelMapper.class);
    private final KeyService keyService = mock(KeyService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final OllamaModelSyncService service =
            new OllamaModelSyncService(channelMapper, keyService, upstream, new ModelCatalogCache(redis));

    @SuppressWarnings("unchecked")
    private void stubRedis() {
        when(redis.opsForValue())
                .thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
    }

    @Test
    void sync_refreshesChannelModelsOnly() throws Exception {
        Channel ch = new Channel();
        ch.setId("c1");
        ch.setType("ollama");
        ch.setBaseUrl("http://x/api");
        ch.setApiKey("");
        when(channelMapper.selectById("c1")).thenReturn(ch);
        when(keyService.decrypt("")).thenReturn("");
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"models\":[{\"name\":\"gemma4\"},{\"name\":\"llava\"}]}");
        when(upstream.get("http://x/api/tags", "")).thenReturn(resp);
        stubRedis();

        OllamaSyncResult result = service.sync("c1");

        assertEquals(2, result.total());
        assertEquals(List.of("gemma4", "llava"), result.models());
        ArgumentCaptor<Channel> chanCaptor = ArgumentCaptor.forClass(Channel.class);
        verify(channelMapper).updateById(chanCaptor.capture());
        assertEquals("gemma4,llava", chanCaptor.getValue().getModels());
    }

    @Test
    void sync_rejectsNonOllamaChannel() {
        Channel ch = new Channel();
        ch.setId("c1");
        ch.setType("openai_compatible");
        when(channelMapper.selectById("c1")).thenReturn(ch);
        try {
            service.sync("c1");
        } catch (com.llmgateway.exception.BusinessException e) {
            assertEquals(40001, e.getCode());
        }
    }
}
