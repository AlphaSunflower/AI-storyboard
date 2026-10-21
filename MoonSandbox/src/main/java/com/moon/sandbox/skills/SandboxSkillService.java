package com.moon.sandbox.skills;

import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathException;
import com.moon.sandbox.session.SandboxSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 按需把镜像里预置的只读技能包物化到单个会话工作区。
 *
 * <p>技能包常驻在镜像的 {@code /opt/moon-skills/<skill>}，但**不会**挂在任何会话工作区，
 * 也不会自动出现在模型上下文里。只有本轮消息显式引用了技能（或该会话此前已经引用过），
 * MoonAgent 才调用这里把 {@code <skill>} 复制到 {@code /workspaces/{conversationId}/skills/<skill>}。
 *
 * <p>物化是幂等的：目标目录里的 {@code .moon-skill-version} 与当前包版本一致时直接返回。
 * 版本由「镜像版本号 + SKILL.md 的 mtime/size」组成，镜像重建后会自动重拷。
 */
@Service
public class SandboxSkillService {

    private static final Logger log = LoggerFactory.getLogger(SandboxSkillService.class);

    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final String VERSION_FILE = ".moon-skill-version";
    private static final String SKILL_FILE = "SKILL.md";

    private final SandboxSessionService sessions;
    private final MoonSandboxProperties properties;

    public SandboxSkillService(SandboxSessionService sessions, MoonSandboxProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    /**
     * 将技能包复制到会话工作区 {@code skills/<skill>}。
     *
     * @return 物化结果；{@code installed=false} 表示版本命中、无需重复复制
     */
    public SandboxSkillResult materialize(String conversationId, String skillName) {
        String skill = requireSkillId(skillName);
        Path source = bundleRoot(skill);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS)) {
            throw new SandboxPathException("skill bundle is not installed: " + skill);
        }
        String version = bundleVersion(skill, source);

        return sessions.withLock(conversationId, () -> {
            Path session = sessions.sessionRoot(conversationId);
            Path skillsRoot = session.resolve("skills").normalize();
            Path target = skillsRoot.resolve(skill).normalize();
            if (!target.startsWith(skillsRoot)) {
                throw new SandboxPathException("skill target escapes the session workspace");
            }

            if (isCurrent(target, version)) {
                return new SandboxSkillResult(skill, relative(session, target),
                        false, 0, 0, version);
            }

            deleteRecursively(target);
            CopyStats stats = copyTree(source, target);
            writeVersion(target, version);
            sessions.touch(conversationId);
            log.info("[SandboxSkill] materialized {} for conversation {}: files={}, bytes={}",
                    skill, conversationId, stats.files(), stats.bytes());
            return new SandboxSkillResult(skill, relative(session, target),
                    true, stats.files(), stats.bytes(), version);
        });
    }

    private Path bundleRoot(String skill) {
        Path root = properties.getSkillBundleRoot().toAbsolutePath().normalize();
        Path source = root.resolve(skill).normalize();
        if (!source.startsWith(root)) {
            throw new SandboxPathException("skill bundle path escapes the bundle root");
        }
        return source;
    }

    private String bundleVersion(String skill, Path source) {
        try {
            Path skillFile = source.resolve(SKILL_FILE);
            return properties.getSkillBundleVersion()
                    + ":" + skill
                    + ":" + Files.getLastModifiedTime(skillFile).toMillis()
                    + ":" + Files.size(skillFile);
        } catch (IOException error) {
            throw new SandboxPathException("unable to read skill bundle version", error);
        }
    }

    private static boolean isCurrent(Path target, String version) {
        if (!Files.isRegularFile(target.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path marker = target.resolve(VERSION_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return version.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException error) {
            return false;
        }
    }

    private static void writeVersion(Path target, String version) {
        try {
            Files.writeString(target.resolve(VERSION_FILE), version, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new SandboxPathException("unable to write skill version marker", error);
        }
    }

    private static CopyStats copyTree(Path source, Path target) {
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(source)) {
            entries = stream.toList();
        } catch (IOException error) {
            throw new SandboxPathException("unable to list skill bundle", error);
        }
        int files = 0;
        long bytes = 0;
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                continue;
            }
            Path destination = target.resolve(source.relativize(entry).toString());
            try {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(entry, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                files++;
                bytes += Files.size(destination);
            } catch (IOException error) {
                throw new SandboxPathException(
                        "unable to materialize skill file: " + entry.getFileName(), error);
            }
        }
        return new CopyStats(files, bytes);
    }

    private static void deleteRecursively(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException error) {
            throw new SandboxPathException("unable to replace existing skill directory", error);
        }
    }

    private static String relative(Path session, Path target) {
        return session.relativize(target).toString().replace('\\', '/');
    }

    private static String requireSkillId(String skillName) {
        String value = skillName == null ? "" : skillName.trim();
        if (!SKILL_ID.matcher(value).matches()) {
            throw new SandboxPathException("invalid skill name");
        }
        return value;
    }

    private record CopyStats(int files, long bytes) {
    }
}
