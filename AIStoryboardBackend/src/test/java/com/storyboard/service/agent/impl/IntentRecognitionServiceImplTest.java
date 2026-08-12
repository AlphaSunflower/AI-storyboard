package com.storyboard.service.agent.impl;

import com.storyboard.entity.AgentMessage;
import com.storyboard.service.agent.IntentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图识别服务单测：规则前置匹配 / LLM JSON 解析 / 白名单兜底。
 * 不加载 Spring 上下文，ChatClient 全 mock —— 规则命中分支本就零 LLM 调用，
 * LLM 分支用 stub 返回固定 JSON 驱动解析逻辑。
 */
class IntentRecognitionServiceImplTest {

    private ChatClient chatClient;
    private IntentRecognitionServiceImpl service;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);

        service = new IntentRecognitionServiceImpl(builder);
        // 默认 stub：LLM 返回空 → 走兜底；各用例按需覆写
        when(responseSpec.content()).thenReturn(null);
    }

    // ==================== 规则前置匹配（零 LLM 调用） ====================

    @Test
    void 规则命中分镜关键词_直接路由aisplit() {
        IntentResult r = service.recognize("帮我把这个剧本生成分镜", null);
        assertEquals("intent-aisplit", r.type());
        assertEquals(1.0, r.confidence());
        assertEquals("rule", r.source());
    }

    @Test
    void 规则命中视频关键词_直接路由video() {
        IntentResult r = service.recognize("给我生成一段视频方案", null);
        assertEquals("intent-video", r.type());
        assertEquals("rule", r.source());
    }

    @Test
    void 规则命中图片关键词_直接路由pic() {
        IntentResult r = service.recognize("画一张海报", null);
        assertEquals("intent-pic", r.type());
        assertEquals("rule", r.source());
    }

    @Test
    void 规则命中改图诉求_路由pic() {
        IntentResult r = service.recognize("去掉画面中的人物", null);
        assertEquals("intent-pic", r.type());
        assertEquals("rule", r.source());
    }

    @Test
    void 规则表顺序_分镜词优先于视频词() {
        // "视频" 不在 video 规则表中（规则表是强信号词），"剧本" 在 aisplit
        IntentResult r = service.recognize("视频剧本怎么改", null);
        assertEquals("intent-aisplit", r.type());
    }

    // ==================== LLM 分支解析 ====================

    @Test
    void llm返回JSON_解析type和confidence() {
        stubLlm("{\"type\":\"intent-video\",\"confidence\":0.9}");
        IntentResult r = service.recognize("帮我做个小动画", null);
        assertEquals("intent-video", r.type());
        assertEquals(0.9, r.confidence());
        assertEquals("llm", r.source());
    }

    @Test
    void llm返回代码块包裹JSON_宽松解析() {
        stubLlm("```json\n{\"type\": \"intent-pic\", \"confidence\": 0.85}\n```");
        IntentResult r = service.recognize("设计一张图", null);
        assertEquals("intent-pic", r.type());
        assertEquals(0.85, r.confidence());
    }

    @Test
    void llm输出裸标识符_兼容解析_置信度05() {
        stubLlm("intent-other");
        IntentResult r = service.recognize("你好", null);
        assertEquals("intent-other", r.type());
        assertEquals(0.5, r.confidence());
        assertEquals("llm", r.source());
    }

    @Test
    void llm输出白名单外类型_兜底other() {
        stubLlm("{\"type\":\"intent-hack\",\"confidence\":0.9}");
        IntentResult r = service.recognize("随便说说", null);
        assertEquals("intent-other", r.type());
        assertEquals("fallback", r.source());
    }

    @Test
    void llm抛出异常_兜底other不阻塞() {
        when(chatClient.prompt().call().content()).thenThrow(new RuntimeException("network down"));
        IntentResult r = service.recognize("继续", List.of());
        assertEquals("intent-other", r.type());
        assertEquals("fallback", r.source());
    }

    @Test
    void llm返回空_兜底other() {
        stubLlm(null);
        IntentResult r = service.recognize("", null);
        assertEquals("intent-other", r.type());
        assertEquals("fallback", r.source());
    }

    @Test
    void cleanType_剥离成对引号() {
        assertEquals("intent-pic", service.cleanType("\"intent-pic\""));
        assertEquals("intent-pic", service.cleanType("'intent-pic'"));
        assertEquals("intent-pic", service.cleanType("  intent-pic  "));
        assertEquals("", service.cleanType(null));
    }

    private void stubLlm(String content) {
        when(chatClient.prompt().call().content()).thenReturn(content);
    }
}
