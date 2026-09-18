package com.llmgateway.service.impl;

import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.storyboard.common.MultipartBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageEditServiceDoubaoTest {

    @Test
    void convertsOpenAiEditMultipartToDoubaoNativeGeneration() throws Exception {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        KeyService keyService = mock(KeyService.class);
        UpstreamClient upstreamClient = mock(UpstreamClient.class);
        CallLogService callLogService = mock(CallLogService.class);

        ModelRoute route = new ModelRoute();
        route.setChannelId("channel-1");
        when(routeMapper.selectList(any())).thenReturn(List.of(route));
        Channel channel = new Channel();
        channel.setId("channel-1");
        channel.setName("doubao");
        channel.setBaseUrl("https://ark.example.com/api/v3");
        channel.setEnabled(true);
        channel.setApiKey("encrypted");
        when(channelMapper.selectById("channel-1")).thenReturn(channel);
        when(keyService.decrypt("encrypted")).thenReturn("key");

        String sse = """
                data: {"type":"image_generation.partial_succeeded","image_index":0,"url":"https://result.example.com/1.png"}

                data: {"type":"image_generation.completed","usage":{"generated_images":1}}

                """;
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        when(upstreamClient.postJsonStream(eq(channel.getBaseUrl()), any(), eq("key"), any()))
                .thenReturn(response);

        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", imageBytes);
        byte[] pixel = imageBytes.toByteArray();
        byte[] multipart = new MultipartBuilder()
                .field("model", "doubao-seedream-4-0-250828")
                .field("prompt", "把椅子改成蓝色")
                .field("size", "2K")
                .field("n", "1")
                .file("image", "source.png", "image/png", pixel)
                .file("mask", "mask.png", "image/png", pixel)
                .build();

        ImageEditServiceImpl service = new ImageEditServiceImpl(
                routeMapper, channelMapper, keyService, upstreamClient, callLogService);
        RouteResult result = service.edit(multipart, "application/octet-stream");

        assertEquals(200, result.status());
        assertTrue(result.body().contains("https://result.example.com/1.png"));
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestBody = ArgumentCaptor.forClass(String.class);
        verify(upstreamClient).postJsonStream(
                eq(channel.getBaseUrl()), path.capture(), eq("key"), requestBody.capture());
        assertEquals("/images/generations", path.getValue());
        assertTrue(requestBody.getValue().contains("\"image\":["));
        assertTrue(requestBody.getValue().contains("洋红色区域"));
    }
}
