package com.moon.moonagent.ai.agent.document;

import jakarta.annotation.PreDestroy;
import org.apache.tika.Tika;
import org.apache.tika.config.OutputLimits;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.xml.sax.SAXException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DocumentParsingService {

    public static final String PARSER_VERSION = "tika-4.1.0";

    private static final Map<String, DocumentType> TYPES = Map.ofEntries(
            Map.entry("doc", DocumentType.WORD),
            Map.entry("docx", DocumentType.WORD),
            Map.entry("xls", DocumentType.EXCEL),
            Map.entry("xlsx", DocumentType.EXCEL),
            Map.entry("ppt", DocumentType.POWERPOINT),
            Map.entry("pptx", DocumentType.POWERPOINT),
            Map.entry("pdf", DocumentType.PDF),
            Map.entry("md", DocumentType.MARKDOWN),
            Map.entry("markdown", DocumentType.MARKDOWN),
            Map.entry("txt", DocumentType.TEXT),
            Map.entry("text", DocumentType.TEXT),
            Map.entry("log", DocumentType.TEXT),
            Map.entry("csv", DocumentType.TEXT),
            Map.entry("tsv", DocumentType.TEXT),
            Map.entry("json", DocumentType.TEXT),
            Map.entry("jsonl", DocumentType.TEXT),
            Map.entry("ndjson", DocumentType.TEXT),
            Map.entry("xml", DocumentType.TEXT),
            Map.entry("yaml", DocumentType.TEXT),
            Map.entry("yml", DocumentType.TEXT),
            Map.entry("html", DocumentType.TEXT),
            Map.entry("htm", DocumentType.TEXT),
            Map.entry("properties", DocumentType.TEXT),
            Map.entry("ini", DocumentType.TEXT),
            Map.entry("conf", DocumentType.TEXT),
            Map.entry("sql", DocumentType.TEXT),
            Map.entry("srt", DocumentType.TEXT),
            Map.entry("vtt", DocumentType.TEXT));

    private final DocumentProperties properties;
    private final Tika tika = new Tika();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DocumentParsingService(DocumentProperties properties) {
        this.properties = properties;
    }

    public ParsedDocument parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentParseException("corrupted", "文档内容为空");
        }
        DocumentType type = typeOf(fileName);
        String detected = tika.detect(bytes, fileName);
        if (!type.supports(detected)) {
            throw new DocumentParseException("unsupported",
                    "文件内容与支持的文档格式不匹配: " + detected);
        }

        Future<ParsedDocument> future = executor.submit(() -> parseNow(bytes, fileName, type));
        try {
            return future.get(Math.max(1, properties.getParseTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DocumentParseException("timeout", "文档解析超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("timeout", "文档解析被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof DocumentParseException parseException) throw parseException;
            if (cause instanceof EncryptedDocumentException) {
                throw new DocumentParseException("encrypted", "文档已加密，暂不支持读取", cause);
            }
            if (cause instanceof TikaException) {
                throw new DocumentParseException("corrupted", "文档解析失败", cause);
            }
            throw new DocumentParseException("corrupted", "文档解析失败", cause);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private ParsedDocument parseNow(byte[] bytes, String fileName, DocumentType type)
            throws IOException, TikaException, SAXException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        LimitedStringWriter writer = new LimitedStringWriter(properties.getMaxParseChars());
        AutoDetectParser parser = new AutoDetectParser();
        OutputLimits limits = new OutputLimits();
        limits.setWriteLimit(Math.max(1, properties.getMaxParseChars()));
        limits.setThrowOnWriteLimit(false);
        ParseContext context = new ParseContext();
        context.set(OutputLimits.class, limits);
        context.set(ParseRecord.class, new ParseRecord());
        try (var stream = org.apache.tika.io.TikaInputStream.get(bytes)) {
            parser.parse(stream, new ToMarkdownContentHandler(writer), metadata, context);
        }
        String markdown = writer.content().strip();
        if (markdown.isBlank()) {
            throw new DocumentParseException("no_text",
                    "文档没有可提取文本；扫描件或纯图片文档暂不支持 OCR");
        }
        ParseRecord record = context.get(ParseRecord.class);
        return new ParsedDocument(
                markdown,
                type.preferredMime(),
                markdown.length(),
                writer.truncated()
                        || (record != null && record.isWriteLimitReached()));
    }

    private static DocumentType typeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new DocumentParseException("unsupported", "缺少可识别的文档扩展名");
        }
        DocumentType type = TYPES.get(name.substring(dot + 1));
        if (type == null) {
            throw new DocumentParseException("unsupported", "暂不支持该文档格式");
        }
        return type;
    }

    private enum DocumentType {
        WORD(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Set.of(
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/x-tika-msoffice",
                        "application/x-tika-ooxml")),
        EXCEL(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Set.of(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/x-tika-msoffice",
                        "application/x-tika-ooxml")),
        POWERPOINT(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                Set.of(
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/x-tika-msoffice",
                        "application/x-tika-ooxml")),
        PDF(
                "application/pdf",
                Set.of("application/pdf")),
        MARKDOWN(
                "text/markdown",
                Set.of("text/markdown", "text/x-markdown", "text/plain")),
        TEXT(
                "text/plain",
                Set.of(
                        "text/plain",
                        "text/x-log",
                        "text/csv",
                        "text/tab-separated-values",
                        "text/html",
                        "text/xml",
                        "text/yaml",
                        "text/x-yaml",
                        "application/json",
                        "application/ld+json",
                        "application/xml",
                        "application/yaml",
                        "application/x-yaml"));

        private final String preferredMime;
        private final Set<String> supportedMimeTypes;

        DocumentType(String preferredMime, Set<String> supportedMimeTypes) {
            this.preferredMime = preferredMime;
            this.supportedMimeTypes = supportedMimeTypes;
        }

        boolean supports(String mimeType) {
            return mimeType != null && supportedMimeTypes.contains(mimeType.toLowerCase(Locale.ROOT));
        }

        String preferredMime() {
            return preferredMime;
        }
    }

    private static final class LimitedStringWriter extends Writer {

        private final int limit;
        private final StringBuilder buffer = new StringBuilder();
        private boolean truncated;

        private LimitedStringWriter(int limit) {
            this.limit = Math.max(1, limit);
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            if (length <= 0) return;
            int remaining = limit - buffer.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(remaining, length);
            buffer.append(chars, offset, accepted);
            if (accepted < length) truncated = true;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String content() {
            return buffer.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
