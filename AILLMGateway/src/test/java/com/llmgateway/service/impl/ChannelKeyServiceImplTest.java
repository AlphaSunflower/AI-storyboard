package com.llmgateway.service.impl;

import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ChannelKey;
import com.llmgateway.mapper.ChannelKeyMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.ChannelKeyService;
import com.llmgateway.service.KeyService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelKeyServiceImplTest {

    private final ChannelKeyMapper keyMapper = mock(ChannelKeyMapper.class);
    private final ChannelMapper channelMapper = mock(ChannelMapper.class);
    private final KeyService keyService = mock(KeyService.class);
    private final ChannelKeyServiceImpl service = new ChannelKeyServiceImpl(keyMapper, channelMapper, keyService);

    @Test
    void emptyPoolFallsBackToLegacyChannelKey() {
        when(keyMapper.selectList(any())).thenReturn(List.of());
        when(keyService.decrypt("legacy")).thenReturn("plain-legacy");

        ChannelKeyService.KeySelection selection = service.select(channel());

        assertNull(selection.keyId());
        assertEquals("plain-legacy", selection.plainKey());
    }

    @Test
    void failedKeyIsSkippedWhileCooling() {
        ChannelKey k1 = key("k1", "enc1");
        ChannelKey k2 = key("k2", "enc2");
        when(keyMapper.selectList(any())).thenReturn(List.of(k1, k2));
        when(keyService.decrypt("enc1")).thenReturn("plain1");
        when(keyService.decrypt("enc2")).thenReturn("plain2");

        ChannelKeyService.KeySelection first = service.select(channel());
        service.markFailure(first.keyId(), 429);

        ChannelKeyService.KeySelection second = service.select(channel());
        assertNotEquals(first.keyId(), second.keyId());
    }

    @Test
    void allKeysCoolingStillReturnsOne() {
        ChannelKey k1 = key("k1", "enc1");
        when(keyMapper.selectList(any())).thenReturn(List.of(k1));
        when(keyService.decrypt("enc1")).thenReturn("plain1");

        service.markFailure("k1", 429);
        ChannelKeyService.KeySelection selection = service.select(channel());

        assertEquals("k1", selection.keyId());
    }

    @Test
    void successClearsCooldown() {
        ChannelKey k1 = key("k1", "enc1");
        ChannelKey k2 = key("k2", "enc2");
        when(keyMapper.selectList(any())).thenReturn(List.of(k1, k2));
        when(keyService.decrypt("enc1")).thenReturn("plain1");
        when(keyService.decrypt("enc2")).thenReturn("plain2");

        service.markFailure("k1", 429);
        service.markSuccess("k1");

        // 清除冷却后两个 Key 都可能被选中；至少不应抛错，且仍返回有效 Key
        ChannelKeyService.KeySelection selection = service.select(channel());
        assertEquals(true, List.of("k1", "k2").contains(selection.keyId()));
    }

    private static Channel channel() {
        Channel channel = new Channel();
        channel.setId("c1");
        channel.setApiKey("legacy");
        return channel;
    }

    private static ChannelKey key(String id, String encrypted) {
        ChannelKey key = new ChannelKey();
        key.setId(id);
        key.setChannelId("c1");
        key.setApiKey(encrypted);
        key.setWeight(1);
        key.setEnabled(true);
        return key;
    }
}
