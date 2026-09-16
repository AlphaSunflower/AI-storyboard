package com.moon.moonagent.config;

import java.time.Duration;

import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI OpenAI OkHttp 客户端超时配置。
 * 生图/视觉模型调用偶发 60-120s，OkHttp 默认 socket timeout 太短会 InterruptedIOException。
 * <p>
 * 坑（2026-08-23 实测）：{@code builder.timeout(Duration)} 只设置 openai-java Timeout 的
 * {@code request}（→ okhttp callTimeout 总超时），而 {@code read/write/connect}（socket 级）留默认。
 * chat 流式（{@code stream().content()}）逐 chunk 读 socket，模型思考停顿 &gt; 默认 readTimeout(~10s)
 * 时 okhttp 抛 {@code InterruptedIOException: timeout} → {@code Socket closed} → {@code Stream failed}，
 * 流被掐断 → 聚合文本截断 → BeanOutputConverter 解析失败（Unexpected end-of-input）。
 * 必须显式设 {@link Timeout#read()}（socket 读超时），用 {@code builder.timeout(Timeout)} 而非 {@code timeout(Duration)}。
 * 画布长任务需要允许模型在工具循环中长时间停顿，显式放宽读写与整个请求的总超时。
 */
@Configuration
public class OpenAiHttpConfig {

    @Bean
    OpenAiHttpClientBuilderCustomizer openAiTimeoutCustomizer() {
        return builder -> builder.timeout(Timeout.builder()
                .connect(Duration.ofSeconds(60))    // 连接建立
                .read(Duration.ofHours(1))          // socket 读超时——流式逐 chunk 关键
                .write(Duration.ofMinutes(5))       // 请求体发送
                .request(Duration.ofHours(1))       // 单次模型请求总超时
                .build());
    }
}
