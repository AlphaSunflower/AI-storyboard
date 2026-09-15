package com.llmgateway.service.impl;

import com.llmgateway.entity.Channel;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.UpstreamClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileUploadServiceImplTest {

    @Test
    void usesVideoGenerationInputPurpose() {
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        KeyService keyService = mock(KeyService.class);
        UpstreamClient upstreamClient = mock(UpstreamClient.class);
        Channel channel = new Channel();
        channel.setType("minimax");
        channel.setBaseUrl("https://api.minimaxi.com");
        channel.setApiKey("encrypted");
        when(channelMapper.selectList(any())).thenReturn(List.of(channel));
        when(keyService.decrypt("encrypted")).thenReturn("secret");
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"file\":{\"file_id\":\"123\"}}");
        when(upstreamClient.postMultipart(
                anyString(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(response);

        FileUploadServiceImpl service = new FileUploadServiceImpl(
                channelMapper, keyService, upstreamClient);
        String fileId = service.upload(
                "multipart/form-data; boundary=b",
                "--b\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nx\r\n--b--\r\n"
                        .getBytes());

        assertEquals("mm_file://123", fileId);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(upstreamClient).postMultipart(
                eq("https://api.minimaxi.com"),
                eq("/v1/files/upload"),
                eq("secret"),
                eq("multipart/form-data; boundary=b"),
                bodyCaptor.capture(),
                eq(300_000L));
        String multipart = new String(bodyCaptor.getValue(), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"purpose\""));
        assertTrue(multipart.contains("video_generation_input"));
    }
}
