package com.moon.moonagent.ai.agent.document;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSummaryServiceTest {

    @Test
    void summarizesAllBoundedChunksAndMergesThem() {
        DocumentProperties properties = new DocumentProperties();
        properties.setSummaryChunkChars(12);
        properties.setSummaryMaxChunks(4);
        properties.setSummaryConcurrency(2);
        DocumentSummaryService service = new DocumentSummaryService(properties, new FakeSummaryModel());

        SummaryResult result = service.summarize(
                "# 标题\n\n第一段内容。\n\n第二段内容。\n\n第三段内容。");

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.summary()).startsWith("合并:");
        assertThat(result.summary()).contains("摘要:");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void fallsBackToDeterministicExcerptWhenModelFails() {
        DocumentProperties properties = new DocumentProperties();
        properties.setSummaryChunkChars(20);
        DocumentSummaryService service = new DocumentSummaryService(properties, new FailingSummaryModel());

        SummaryResult result = service.summarize("# 标题\n\n正文内容");

        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.summary()).contains("# 标题");
        assertThat(result.summary()).contains("正文内容");
    }

    @Test
    void limitsChunkSummariesToConfiguredConcurrency() throws Exception {
        DocumentProperties properties = new DocumentProperties();
        properties.setSummaryChunkChars(20);
        properties.setSummaryMaxChunks(4);
        properties.setSummaryConcurrency(2);
        ControlledSummaryModel model = new ControlledSummaryModel();
        DocumentSummaryService service = new DocumentSummaryService(properties, model);

        CompletableFuture<SummaryResult> result = CompletableFuture.supplyAsync(
                () -> service.summarize(
                        "第一段".repeat(10) + "\n\n"
                                + "第二段".repeat(10) + "\n\n"
                                + "第三段".repeat(10) + "\n\n"
                                + "第四段".repeat(10)));
        assertThat(model.twoStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(model.maxActive.get()).isLessThanOrEqualTo(2);
        model.release.countDown();

        assertThat(result.get(2, TimeUnit.SECONDS).status()).isEqualTo("ready");
        assertThat(model.maxActive.get()).isLessThanOrEqualTo(2);
    }

    private static final class FakeSummaryModel implements DocumentSummaryModel {
        @Override
        public String summarizeChunk(String markdown) {
            return "摘要:" + markdown;
        }

        @Override
        public String merge(List<String> summaries) {
            return "合并:" + String.join("|", summaries);
        }
    }

    private static final class FailingSummaryModel implements DocumentSummaryModel {
        @Override
        public String summarizeChunk(String markdown) {
            throw new IllegalStateException("model unavailable");
        }

        @Override
        public String merge(List<String> summaries) {
            throw new IllegalStateException("model unavailable");
        }
    }

    private static final class ControlledSummaryModel implements DocumentSummaryModel {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch twoStarted = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String summarizeChunk(String markdown) {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            twoStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return "摘要";
        }

        @Override
        public String merge(List<String> summaries) {
            return String.join("|", summaries);
        }
    }
}
