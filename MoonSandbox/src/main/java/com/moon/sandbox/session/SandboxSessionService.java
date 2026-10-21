package com.moon.sandbox.session;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

@Service
public class SandboxSessionService {

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "log", "html", "htm",
            "py", "pyw", "js", "mjs", "cjs", "jsx", "ts", "tsx",
            "java", "kt", "kts", "go", "rs", "rb", "php",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "cs",
            "swift", "m", "mm", "scala", "dart", "lua", "pl", "pm", "r",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql",
            "vue", "svelte", "astro", "toml", "gradle", "ini", "conf", "cfg",
            "config", "properties", "env", "makefile", "dockerfile", "gitignore",
            "editorconfig", "tex", "rst", "proto", "graphql", "gql");

    private final SandboxPathResolver paths;
    private final MoonSandboxProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SandboxSessionService(SandboxPathResolver paths, MoonSandboxProperties properties) {
        this.paths = paths;
        this.properties = properties;
    }

    public WriteFileResult write(String conversationId, String relativePath,
                                 String content, WriteMode mode) {
        return writeBytes(conversationId, relativePath,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8), mode);
    }

    public WriteFileResult writeBytes(String conversationId, String relativePath,
                                      byte[] content, WriteMode mode) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            Path target = paths.resolve(session, requireFile(relativePath));
            byte[] bytes = content == null ? new byte[0] : content;
            if (bytes.length > properties.getMaxWriteBytes()) {
                throw new SandboxPathException("file exceeds max write size");
            }
            Path parent = target.getParent();
            try {
                Files.createDirectories(parent);
                WriteMode effective = mode == null ? WriteMode.OVERWRITE : mode;
                switch (effective) {
                    case CREATE -> Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                    case APPEND -> Files.write(target, bytes, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    case OVERWRITE -> Files.write(target, bytes, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
                paths.touch(session);
                return new WriteFileResult(toRelative(session, target),
                        Files.size(target), sha256(bytes));
            } catch (java.nio.file.FileAlreadyExistsException error) {
                throw new SandboxPathException("file already exists", error);
            } catch (IOException error) {
                throw new SandboxPathException("unable to write file", error);
            }
        });
    }

    public byte[] exportArchive(String conversationId) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                 ZipOutputStream zip = new ZipOutputStream(buffer);
                 Stream<Path> stream = Files.walk(session)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    if (path.getFileName().toString().equals(".last-used")) continue;
                    String name = session.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
                zip.finish();
                paths.touch(session);
                return buffer.toByteArray();
            } catch (IOException error) {
                throw new SandboxPathException("unable to export workspace archive", error);
            }
        });
    }

    public void importArchive(String conversationId, byte[] archive) {
        withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            if (archive == null || archive.length == 0) return null;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    Path target = paths.resolve(session, entry.getName());
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    zip.closeEntry();
                }
                paths.touch(session);
                return null;
            } catch (IOException error) {
                throw new SandboxPathException("unable to import workspace archive", error);
            }
        });
    }

    public ReadFileResult read(String conversationId, String relativePath,
                               int requestedOffset, int requestedMaxChars) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            Path target = paths.resolve(session, requireFile(relativePath));
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxPathException("file does not exist");
            }
            try {
                String text = Files.readString(target, StandardCharsets.UTF_8);
                int offset = Math.max(0, Math.min(requestedOffset, text.length()));
                int maxChars = requestedMaxChars <= 0
                        ? properties.getDefaultReadChars()
                        : Math.min(requestedMaxChars, properties.getMaxReadChars());
                int end = Math.min(text.length(), offset + maxChars);
                String content = text.substring(offset, end);
                paths.touch(session);
                return new ReadFileResult(toRelative(session, target), content,
                        offset, end, text.length(), end < text.length());
            } catch (IOException error) {
                throw new SandboxPathException("unable to read file", error);
            }
        });
    }

    public List<FileEntry> list(String conversationId, String relativePath) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            Path directory = paths.resolve(session, relativePath);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of(entry(session, directory));
            }
            try (Stream<Path> stream = Files.walk(directory)) {
                List<FileEntry> entries = stream
                        .filter(path -> !path.equals(session.resolve(".last-used")))
                        .filter(path -> !path.getFileName().toString().equals(".runner"))
                        .map(path -> entry(session, path))
                        .sorted(Comparator.comparing(FileEntry::path))
                        .toList();
                paths.touch(session);
                return entries;
            } catch (IOException error) {
                throw new SandboxPathException("unable to list files", error);
            }
        });
    }

    public List<FileEntry> listOutput(String conversationId) {
        return list(conversationId, "output").stream()
                .filter(entry -> !entry.directory())
                .toList();
    }

    public byte[] readBytes(String conversationId, String relativePath) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            Path target = paths.resolve(session, requireFile(relativePath));
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxPathException("file does not exist");
            }
            try {
                paths.touch(session);
                return Files.readAllBytes(target);
            } catch (IOException error) {
                throw new SandboxPathException("unable to read file bytes", error);
            }
        });
    }

    public Path sessionRoot(String conversationId) {
        return paths.ensureSession(conversationId);
    }

    public void touch(String conversationId) {
        paths.touch(paths.ensureSession(conversationId));
    }

    public boolean cleanup(String conversationId) {
        ReentrantLock lock = lock(conversationId);
        lock.lock();
        try {
            Path session = paths.root().resolve(conversationId);
            if (!Files.exists(session, LinkOption.NOFOLLOW_LINKS)) return false;
            try (Stream<Path> stream = Files.walk(session)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException error) {
                        throw new SandboxPathException("unable to cleanup session", error);
                    }
                });
            }
            locks.remove(conversationId);
            return true;
        } catch (IOException error) {
            throw new SandboxPathException("unable to cleanup session", error);
        } finally {
            lock.unlock();
        }
    }

    public int cleanupExpired() {
        Instant cutoff = Instant.now().minus(properties.getIdleExpiry());
        if (!Files.isDirectory(paths.root())) return 0;
        int removed = 0;
        try (Stream<Path> sessions = Files.list(paths.root())) {
            for (Path session : sessions.filter(Files::isDirectory).toList()) {
                Path marker = session.resolve(".last-used");
                Instant modified = Files.exists(marker)
                        ? Files.getLastModifiedTime(marker).toInstant()
                        : Files.getLastModifiedTime(session).toInstant();
                if (modified.isBefore(cutoff) && cleanup(session.getFileName().toString())) {
                    removed++;
                }
            }
            return removed;
        } catch (IOException error) {
            throw new SandboxPathException("unable to scan sandbox sessions", error);
        }
    }

    /**
     * 清理活跃会话里的临时文件：{@code tmp/} 与 {@code work/.runner/}。
     *
     * <p>这两处是「跑一次就多一份」的典型（run_python_script 每次写一个 UUID 脚本、
     * Python/ffmpeg 的中间文件落在 tmp/）。只删超过 {@code transient-ttl}（默认 1h）的文件，
     * 不会碰到正在执行的进程，也不碰用户产物（{@code output/}）和 {@code work/} 里的其它文件。
     *
     * @return 删除的文件数
     */
    public int cleanupTransient() {
        Instant cutoff = Instant.now().minus(properties.getTransientTtl());
        if (!Files.isDirectory(paths.root())) return 0;
        int removed = 0;
        try (Stream<Path> sessions = Files.list(paths.root())) {
            for (Path session : sessions.filter(Files::isDirectory).toList()) {
                removed += purgeOlderThan(session.resolve("tmp"), cutoff);
                removed += purgeOlderThan(session.resolve("work").resolve(".runner"), cutoff);
            }
            return removed;
        } catch (IOException error) {
            throw new SandboxPathException("unable to scan sandbox sessions", error);
        }
    }

    private static int purgeOlderThan(Path directory, Instant cutoff) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed = 0;
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(directory)) continue;
                try {
                    boolean directoryEntry = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                    if (directoryEntry) {
                        // 子项已按逆序删过；这里只删空目录，非空目录 delete 会失败并被忽略
                        Files.deleteIfExists(path);
                        continue;
                    }
                    if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                            .toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 正在使用的文件（Windows 句柄未释放）留到下一轮清理
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时跳过，不阻塞其它会话清理
        }
        return removed;
    }

    public <T> T withLock(String conversationId, Callable<T> action) {
        ReentrantLock lock = lock(conversationId);
        lock.lock();
        try {
            return action.call();
        } catch (SandboxPathException error) {
            throw error;
        } catch (Exception error) {
            throw new SandboxPathException("sandbox operation failed", error);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lock(String conversationId) {
        return locks.computeIfAbsent(conversationId, ignored -> new ReentrantLock(true));
    }

    private static String requireFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxPathException("file path is required");
        }
        return relativePath.trim();
    }

    private static FileEntry entry(Path session, Path path) {
        try {
            String relative = toRelative(session, path);
            boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            long size = directory ? 0 : Files.size(path);
            OffsetDateTime modified = OffsetDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), ZoneOffset.UTC);
            return new FileEntry(relative, directory, size, mimeType(relative), modified);
        } catch (IOException error) {
            throw new SandboxPathException("unable to inspect file", error);
        }
    }

    private static String toRelative(Path session, Path target) {
        return session.relativize(target).toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String mimeType(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".tsv")) return "text/tab-separated-values";
        if (name.endsWith(".json") || name.endsWith(".jsonl")
                || name.endsWith(".ndjson")) return "application/json";
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (name.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".txt") || name.endsWith(".log")) return "text/plain";
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "yaml", "yml" -> "application/yaml";
            case "xml" -> "text/plain";
            default -> PLAIN_TEXT_EXTENSIONS.contains(ext)
                    ? "text/plain" : "application/octet-stream";
        };
    }
}
