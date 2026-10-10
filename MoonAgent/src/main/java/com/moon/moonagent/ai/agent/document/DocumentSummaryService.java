package com.moon.moonagent.ai.agent.document;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
public class DocumentSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSummaryService.class);

    private final DocumentProperties properties;
    private final DocumentSummaryModel model;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DocumentSummaryService(DocumentProperties properties, DocumentSummaryModel model) {
        this.properties = properties;
        this.model = model;
    }

    public SummaryResult summarize(String markdown) {
        Chunking chunking = chunk(markdown);
        try {
            List<Future<String>> futures = new ArrayList<>();
            Semaphore permits = new Semaphore(
                    Math.max(1, properties.getSummaryConcurrency()));
            for (String chunk : chunking.chunks()) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return model.summarizeChunk(chunk);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<String> summaries = new ArrayList<>();
            for (Future<String> future : futures) {
                summaries.add(future.get());
            }
            String summary = summaries.size() == 1 ? summaries.getFirst() : model.merge(summaries);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("summary model returned empty content");
            }
            return new SummaryResult(
                    truncate(summary, properties.getSummaryMaxChars()),
                    "ready",
                    chunking.truncated());
        } catch (Exception error) {
            log.warn("文档摘要模型失败，使用结构化降级摘要: {}", error.getMessage());
            return new SummaryResult(
                    fallback(markdown),
                    "partial",
                    chunking.truncated());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    Chunking chunk(String markdown) {
        String text = markdown == null ? "" : markdown.strip();
        if (text.isEmpty()) return new Chunking(List.of(""), false);

        int target = Math.max(20, properties.getSummaryChunkChars());
        int maxChunks = Math.max(1, properties.getSummaryMaxChunks());
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            appendParagraph(chunks, current, paragraph, target, maxChunks);
            if (chunks.size() >= maxChunks) break;
        }
        if (current.length() > 0 && chunks.size() < maxChunks) {
            chunks.add(current.toString().strip());
        }
        boolean truncated = chunks.size() >= maxChunks && joinedLength(chunks) < text.length();
        return new Chunking(List.copyOf(chunks), truncated);
    }

    private static void appendParagraph(List<String> chunks, StringBuilder current,
                                        String paragraph, int target, int maxChunks) {
        if (chunks.size() >= maxChunks) return;
        String value = paragraph == null ? "" : paragraph.strip();
        if (value.isEmpty()) return;
        if (current.length() > 0 && current.length() + value.length() + 2 > target) {
            chunks.add(current.toString().strip());
            current.setLength(0);
        }
        if (value.length() <= target) {
            if (current.length() > 0) current.append("\n\n");
            current.append(value);
            return;
        }
        int offset = 0;
        while (offset < value.length() && chunks.size() < maxChunks) {
            int end = Math.min(value.length(), offset + target);
            if (current.length() > 0) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            chunks.add(value.substring(offset, end).strip());
            offset = end;
        }
    }

    private static int joinedLength(List<String> chunks) {
        return chunks.stream().mapToInt(String::length).sum();
    }

    private String fallback(String markdown) {
        String text = markdown == null ? "" : markdown.strip();
        List<String> headings = text.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("#"))
                .limit(12)
                .toList();
        String excerpt = truncate(text, Math.max(400, properties.getSummaryMaxChars() - 300));
        StringBuilder summary = new StringBuilder();
        if (!headings.isEmpty()) {
            summary.append("文档结构:\n").append(String.join("\n", headings)).append("\n\n");
        }
        summary.append("内容摘录:\n").append(excerpt);
        return truncate(summary.toString(), properties.getSummaryMaxChars());
    }

    private static String truncate(String value, int maxChars) {
        int limit = Math.max(1, maxChars);
        String text = value == null ? "" : value.strip();
        return text.length() <= limit ? text : text.substring(0, limit).strip() + "...";
    }

    record Chunking(List<String> chunks, boolean truncated) {
    }
}
