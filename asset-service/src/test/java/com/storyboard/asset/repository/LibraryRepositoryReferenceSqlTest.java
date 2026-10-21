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

    /** 挑片标记是标注，不是内容修订：写标记不能递增 version，否则点一次星就跳一个版本。 */
    @Test
    void marksSqlDoesNotBumpVersion() {
        assertThat(LibraryRepository.UPDATE_MARKS_SQL)
                .contains("rating = :rating")
                .contains("flag = :flag")
                .contains("color_label = :colorLabel")
                .contains("updated_at = now()")
                .doesNotContainIgnoringCase("version");
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