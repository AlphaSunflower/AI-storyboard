package com.moon.sandbox.session;

import org.springframework.stereotype.Service;

import java.io.IOException;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
public class SandboxSessionService {

    private final SandboxPathResolver paths;
    private final MoonSandboxProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SandboxSessionService(SandboxPathResolver paths, MoonSandboxProperties properties) {
        this.paths = paths;
        this.properties = properties;
    }

    public WriteFileResult write(String conversationId, String relativePath,
                                 String content, WriteMode mode) {
        return withLock(conversationId, () -> {
            Path session = paths.ensureSession(conversationId);
            Path target = paths.resolve(session, requireFile(relativePath));
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
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
        if (name.endsWith(".txt") || name.endsWith(".log")) return "text/plain";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return Map.of(
                "yaml", "application/yaml",
                "yml", "application/yaml",
                "xml", "application/xml").getOrDefault(
                name.substring(name.lastIndexOf('.') + 1), "application/octet-stream");
    }
}
