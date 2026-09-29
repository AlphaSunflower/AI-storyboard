package com.storyboard.asset.cleanup;

import com.storyboard.asset.repository.LegacyObjectReferenceRepository;
import com.storyboard.asset.repository.LibraryRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 唯一的 object key 引用判定组件：扫描入队与删除前二次确认都走这里。
 * 任一来源查询失败时直接抛出，调用方必须按 fail-safe 跳过本轮，不得吞错。
 */
@Component
public class ObjectReferenceGuard {

    private final LibraryRepository libraryRepository;
    private final LegacyObjectReferenceRepository legacyRepository;

    public ObjectReferenceGuard(
            LibraryRepository libraryRepository,
            LegacyObjectReferenceRepository legacyRepository
    ) {
        this.libraryRepository = libraryRepository;
        this.legacyRepository = legacyRepository;
    }

    public boolean isReferenced(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return false;
        return !referencedObjectKeys(List.of(objectKey)).isEmpty();
    }

    /** 返回 candidates 中仍被任一保护来源引用的子集；查询失败时抛出，不做任何吞错。 */
    public Set<String> referencedObjectKeys(Collection<String> candidates) {
        Set<String> pending = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) pending.add(candidate);
            }
        }
        if (pending.isEmpty()) return Set.of();

        Set<String> referenced = new LinkedHashSet<>();
        collectHits(referenced, pending, libraryRepository.referencedObjectKeys(pending));
        pending.removeAll(referenced);
        if (pending.isEmpty()) return referenced;

        collectHits(referenced, pending, libraryRepository.activeUploadSessionObjectKeys(pending));
        pending.removeAll(referenced);
        if (pending.isEmpty()) return referenced;

        collectHits(referenced, pending, legacyRepository.referencedKeys(pending));
        return referenced;
    }

    /** 只接受仍属于待判定集合的命中，保证返回值是 candidates 的子集。 */
    private static void collectHits(Set<String> referenced, Set<String> pending, Set<String> hits) {
        if (hits == null || hits.isEmpty()) return;
        Set<String> filtered = new LinkedHashSet<>(hits);
        filtered.retainAll(pending);
        referenced.addAll(filtered);
    }
}