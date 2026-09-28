package com.storyboard.asset.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegacyObjectReferenceRepositoryTest {

    @Test
    void coversEveryProtectedLegacySource() {
        assertThat(LegacyObjectReferenceRepository.CANVAS_ASSETS_SQL)
                .contains("public.canvas_assets").contains("object_key IN (:keys)");
        assertThat(LegacyObjectReferenceRepository.ASSET_IMAGES_SQL)
                .contains("public.asset_images").contains("url");
        assertThat(LegacyObjectReferenceRepository.SCENE_REFERENCE_IMAGES_SQL)
                .contains("public.scene_reference_images").contains("image_url");
        assertThat(LegacyObjectReferenceRepository.SCENES_IMAGE_SQL)
                .contains("public.scenes").contains("image_url");
        assertThat(LegacyObjectReferenceRepository.SCENES_VIDEO_SQL)
                .contains("public.scenes").contains("video_url");
        assertThat(LegacyObjectReferenceRepository.AGENT_ASSET_URL_SQL)
                .contains("public.agent_assets").contains("url");
        assertThat(LegacyObjectReferenceRepository.AGENT_RESULT_URLS_SQL)
                .contains("public.agent_assets").contains("result_urls");
    }

    @Test
    void normalizesApiFilesPrefixAndStripsQueryString() {
        assertThat(LegacyObjectReferenceRepository.ASSET_IMAGES_SQL)
                .contains("/api/files/").contains("split_part").contains("?");
        assertThat(LegacyObjectReferenceRepository.SCENES_VIDEO_SQL)
                .contains("/api/files/").contains("video_url");
    }

    @Test
    void emptyCandidatesSkipDatabase() {
        JdbcClient jdbc = mock(JdbcClient.class);
        LegacyObjectReferenceRepository repository = new LegacyObjectReferenceRepository(jdbc);

        assertThat(repository.referencedKeys(List.of())).isEmpty();
        assertThat(repository.referencedKeys(null)).isEmpty();

        verifyNoInteractions(jdbc);
    }
}