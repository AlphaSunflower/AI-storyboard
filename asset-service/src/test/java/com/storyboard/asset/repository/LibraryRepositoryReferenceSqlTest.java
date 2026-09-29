package com.storyboard.asset.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryRepositoryReferenceSqlTest {

    @Test
    void referencedKeysSqlCoversAllLibrarySources() {
        String sql = LibraryRepository.REFERENCED_OBJECT_KEYS_SQL;
        assertThat(sql).contains("asset.library_media");
        assertThat(sql).contains("asset.library_group_items");
        assertThat(sql).contains("asset.library_group_snapshot_items");
        assertThat(sql).contains("IN (:keys)");
    }

    @Test
    void referencedKeysSqlExcludesPurgeStates() {
        assertThat(LibraryRepository.REFERENCED_OBJECT_KEYS_SQL)
                .contains("NOT IN ('PURGE_PENDING', 'PURGED')");
    }

    @Test
    void uploadSessionSqlOnlyProtectsLiveSessions() {
        assertThat(LibraryRepository.ACTIVE_UPLOAD_SESSION_KEYS_SQL)
                .contains("asset.library_upload_sessions")
                .contains("status = 'CREATED'")
                .contains("expires_at > now()")
                .contains("IN (:keys)");
    }
}