package com.moon.moonagent.ai.agent.agentscope.nacos;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Selects one complete AgentScope skill set before serving reads.
 *
 * <p>Nacos is preferred when the configured version can load every expected
 * skill. If any expected skill is missing or the Nacos client fails, the
 * repository switches to the complete classpath set. It never merges entries
 * from both sources.
 */
public class ResilientAgentSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(ResilientAgentSkillRepository.class);

    private final List<String> expectedSkillNames;
    private final boolean allowClasspathFallback;
    private final Supplier<AgentSkillRepository> primarySupplier;
    private final AgentSkillRepository classpathFallback;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Snapshot selected;

    public ResilientAgentSkillRepository(
            List<String> expectedSkillNames,
            boolean allowClasspathFallback,
            Supplier<AgentSkillRepository> primarySupplier,
            AgentSkillRepository classpathFallback) {
        this.expectedSkillNames = expectedSkillNames == null
                ? List.of()
                : expectedSkillNames.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .distinct()
                        .toList();
        this.allowClasspathFallback = allowClasspathFallback;
        this.primarySupplier = Objects.requireNonNull(primarySupplier, "primarySupplier");
        this.classpathFallback = Objects.requireNonNull(classpathFallback, "classpathFallback");
    }

    @Override
    public AgentSkill getSkill(String skillName) {
        ensureOpen();
        Snapshot snapshot = selected();
        try {
            return snapshot.repository().getSkill(skillName);
        } catch (RuntimeException primaryFailure) {
            if (snapshot.fromPrimary()) {
                return fallbackAfter(primaryFailure).repository().getSkill(skillName);
            }
            throw primaryFailure;
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        ensureOpen();
        return selected().names();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        ensureOpen();
        return selected().skills();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        ensureOpen();
        Snapshot snapshot = selected();
        try {
            return snapshot.repository().skillExists(skillName);
        } catch (RuntimeException primaryFailure) {
            if (snapshot.fromPrimary()) {
                return fallbackAfter(primaryFailure).repository().skillExists(skillName);
            }
            throw primaryFailure;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        ensureOpen();
        return selected().repository().getRepositoryInfo();
    }

    @Override
    public String getSource() {
        ensureOpen();
        return selected().repository().getSource();
    }

    @Override
    public void setWriteable(boolean writeable) {
        // Both backing repositories are read-only.
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /** Status suitable for health/readiness diagnostics. */
    public String selectedSource() {
        Snapshot snapshot = selected;
        return snapshot == null ? "unresolved" : snapshot.source();
    }

    /** Last recovery reason, or {@code null} when the primary source is selected. */
    public String fallbackReason() {
        Snapshot snapshot = selected;
        return snapshot == null ? null : snapshot.fallbackReason();
    }

    /** Allows a later call to re-evaluate Nacos after an operator fixes it. */
    public void refresh() {
        ensureOpen();
        selected = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Snapshot snapshot = selected;
        if (snapshot != null && snapshot.fromPrimary()) {
            closeQuietly(snapshot.repository());
        }
        closeQuietly(classpathFallback);
    }

    private Snapshot selected() {
        Snapshot current = selected;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (selected == null) {
                selected = selectSnapshot();
            }
            return selected;
        }
    }

    private Snapshot selectSnapshot() {
        RuntimeException primaryFailure = null;
        try {
            AgentSkillRepository primary = primarySupplier.get();
            if (primary != null) {
                Snapshot snapshot = validatePrimary(primary);
                log.info("[NacosAssets] using skill repository {} for {} skills",
                        snapshot.source(), snapshot.skills().size());
                return snapshot;
            }
        } catch (RuntimeException exception) {
            primaryFailure = exception;
        }

        if (!allowClasspathFallback) {
            throw new IllegalStateException(
                    "Nacos skill repository unavailable and classpath fallback is disabled",
                    primaryFailure);
        }

        Snapshot fallback = snapshotFrom(
                classpathFallback,
                "classpath",
                false,
                primaryFailure == null ? "Nacos assets disabled" : rootMessage(primaryFailure));
        log.warn("[NacosAssets] using classpath skill fallback: {}", fallback.fallbackReason());
        return fallback;
    }

    private Snapshot validatePrimary(AgentSkillRepository primary) {
        List<AgentSkill> loaded = primary.getAllSkills();
        Map<String, AgentSkill> byName = indexByName(loaded);
        List<String> missing = expectedSkillNames.stream()
                .filter(name -> !byName.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Nacos skill assets are incomplete; missing=" + missing);
        }
        return snapshot(primary, byName, "nacos", true, null);
    }

    private Snapshot fallbackAfter(RuntimeException primaryFailure) {
        synchronized (this) {
            Snapshot current = selected;
            if (current != null && !current.fromPrimary()) {
                return current;
            }
            if (!allowClasspathFallback) {
                throw new IllegalStateException(
                        "Nacos skill repository failed and classpath fallback is disabled",
                        primaryFailure);
            }
            Snapshot fallback = snapshotFrom(
                    classpathFallback,
                    "classpath",
                    false,
                    rootMessage(primaryFailure));
            selected = fallback;
            log.warn("[NacosAssets] switched to classpath skill fallback: {}",
                    fallback.fallbackReason());
            return fallback;
        }
    }

    private static Snapshot snapshotFrom(
            AgentSkillRepository repository,
            String source,
            boolean fromPrimary,
            String fallbackReason) {
        return snapshot(
                repository,
                indexByName(repository.getAllSkills()),
                source,
                fromPrimary,
                fallbackReason);
    }

    private static Snapshot snapshot(
            AgentSkillRepository repository,
            Map<String, AgentSkill> skills,
            String source,
            boolean fromPrimary,
            String fallbackReason) {
        return new Snapshot(
                repository,
                List.copyOf(skills.keySet()),
                List.copyOf(skills.values()),
                source,
                fromPrimary,
                fallbackReason);
    }

    private static Map<String, AgentSkill> indexByName(List<AgentSkill> skills) {
        Map<String, AgentSkill> indexed = new LinkedHashMap<>();
        if (skills == null) {
            return indexed;
        }
        for (AgentSkill skill : skills) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                continue;
            }
            indexed.put(skill.getName().trim(), skill);
        }
        return indexed;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ResilientAgentSkillRepository has been closed");
        }
    }

    private static void closeQuietly(AgentSkillRepository repository) {
        try {
            repository.close();
        } catch (RuntimeException exception) {
            log.debug("[NacosAssets] failed to close skill repository", exception);
        }
    }

    private record Snapshot(
            AgentSkillRepository repository,
            List<String> names,
            List<AgentSkill> skills,
            String source,
            boolean fromPrimary,
            String fallbackReason) {

        private Snapshot {
            names = names == null ? List.of() : List.copyOf(new ArrayList<>(names));
            skills = skills == null ? List.of() : List.copyOf(new ArrayList<>(skills));
        }
    }
}
