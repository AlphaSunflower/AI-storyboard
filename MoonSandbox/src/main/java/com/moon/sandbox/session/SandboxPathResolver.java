package com.moon.sandbox.session;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Component
public class SandboxPathResolver {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final Path root;

    @Autowired
    public SandboxPathResolver(MoonSandboxProperties properties) {
        this(properties.getWorkspaceRoot());
    }

    public SandboxPathResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new SandboxPathException("unable to create sandbox root", e);
        }
    }

    public Path root() {
        return root;
    }

    public Path ensureSession(String conversationId) {
        Path session = root.resolve(sessionId(conversationId));
        try {
            Files.createDirectories(session.resolve("work"));
            Files.createDirectories(session.resolve("output"));
            Files.createDirectories(session.resolve("vendor"));
            Files.createDirectories(session.resolve("tmp"));
            touch(session);
            return session;
        } catch (IOException e) {
            throw new SandboxPathException("unable to create sandbox session", e);
        }
    }

    public Path resolve(Path session, String relativePath) {
        if (session == null) throw new SandboxPathException("session is required");
        String value = relativePath == null ? "" : relativePath.trim();
        if (value.isBlank()) return session;
        if (value.startsWith("/") || value.startsWith("\\")) {
            throw new SandboxPathException("absolute paths are not allowed");
        }
        Path normalizedSession = session.toAbsolutePath().normalize();
        Path target = normalizedSession.resolve(value).normalize();
        if (!target.startsWith(normalizedSession)) {
            throw new SandboxPathException("path escape is not allowed");
        }
        assertNoSymlinkEscape(normalizedSession, target);
        return target;
    }

    public void touch(Path session) {
        try {
            Files.writeString(session.resolve(".last-used"), Long.toString(System.currentTimeMillis()));
        } catch (IOException e) {
            throw new SandboxPathException("unable to update session activity", e);
        }
    }

    private static String sessionId(String conversationId) {
        String value = conversationId == null ? "" : conversationId.trim();
        if (!SESSION_ID.matcher(value).matches()) {
            throw new SandboxPathException("invalid conversation id");
        }
        return value;
    }

    private static void assertNoSymlinkEscape(Path session, Path target) {
        Path sessionReal;
        try {
            sessionReal = session.toRealPath();
        } catch (IOException e) {
            throw new SandboxPathException("session does not exist", e);
        }
        Path cursor = session;
        Path relative = session.relativize(target);
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)) {
                throw new SandboxPathException("symbolic links are not allowed");
            }
            if (!part.equals(relative.getName(relative.getNameCount() - 1))
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxPathException("path parent is not a directory");
            }
            try {
                Path real = cursor.toRealPath();
                if (!real.startsWith(sessionReal)) {
                    throw new SandboxPathException("path escape is not allowed");
                }
            } catch (IOException e) {
                throw new SandboxPathException("unable to resolve path", e);
            }
        }
    }
}
