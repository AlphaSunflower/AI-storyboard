package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetMarkRequest;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.BulkMarkRequest;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.LibraryOrigin;
import com.storyboard.asset.client.AssetContracts.LibraryGroupInsight;
import com.storyboard.asset.client.AssetContracts.OutputConversationSummary;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.FileAccessUrlSigner;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.AssetPurgeService;
import com.storyboard.asset.service.ProjectAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetCatalogServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private FileAccessUrlSigner fileAccessUrlSigner;
    private AssetCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        fileAccessUrlSigner = mock(FileAccessUrlSigner.class);
        when(fileAccessUrlSigner.signIfLocal(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new AssetCatalogServiceImpl(
                repository, projectAccess, mock(MediaUrlSigner.class), fileAccessUrlSigner,
                new AssetPurgeService(repository),
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
    }

    @Test
    void listOutputConversationsGroupsOutputByConversation() {
        when(repository.listOutputConversations(
                eq("u1"), eq("p1"), nullable(String.class), nullable(AssetType.class),
                any(), eq(TagMode.ALL), nullable(Integer.class), nullable(String.class),
                nullable(String.class)))
                .thenReturn(List.of(
                        new OutputConversationSummary(
                                "conv-1", 3, 2, 1, 0,
                                "/api/files/images/a.png",
                                List.of("/api/files/images/a.png", "/api/files/images/b.png"),
                                "主视觉", OffsetDateTime.parse("2026-10-18T10:00:00+08:00")),
                        new OutputConversationSummary(
                                null, 1, 1, 0, 0, null, List.of(),
                                "历史产出", OffsetDateTime.parse("2026-10-17T10:00:00+08:00"))));

        List<OutputConversationSummary> groups = service.listOutputConversations(
                "u1", "p1", null, null, null, TagMode.ALL);

        verify(projectAccess).requireOwned("u1", "p1");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).conversationId()).isEqualTo("conv-1");
        assertThat(groups.get(0).assetCount()).isEqualTo(3);
        assertThat(groups.get(0).previewUrls())
                .containsExactly("/api/files/images/a.png", "/api/files/images/b.png");
        assertThat(groups.get(1).conversationId()).isNull();
    }

    @Test
    void listOutputConversationsRequiresProject() {
        assertThatThrownBy(() -> service.listOutputConversations(
                "u1", "", null, null, null, TagMode.ALL))
                .hasMessageContaining("请先选择项目");
    }

    @Test
    void listGroupInsightsRequiresOwnershipAndDelegates() {        when(repository.listGroupInsights("u1", "p1")).thenReturn(List.of(
                new LibraryGroupInsight("g1", 3, 2, 1,
                        OffsetDateTime.parse("2026-10-18T10:00:00+08:00"))));

        List<LibraryGroupInsight> insights = service.listGroupInsights("u1", "p1");

        verify(projectAccess).requireOwned("u1", "p1");
        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().groupId()).isEqualTo("g1");
        assertThat(insights.getFirst().mediaCount()).isEqualTo(2);
        assertThat(insights.getFirst().lastSnapshotAt()).isNotNull();
    }

    @Test
    void projectAssetRequiresOwnershipValidation() {
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));

        service.create("u1", new CreateAssetRequest(
                "u1", "p1", "IMAGE", null, "demo", null, "upload"), "idem");

        verify(projectAccess).requireOwned("u1", "p1");
    }

    @Test
    void listPassesIncludeGlobalAndOriginToRepository() {
        when(repository.searchAssets(
                anyString(), any(), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(AssetType.class), any(), any(), eq(false), eq(true), eq(LibraryOrigin.GENERATED),
                eq(1), eq(40)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 40));

        service.list("u1", AssetScope.PROJECT, "p1", null, null, null, null, TagMode.ALL, false,
                true, LibraryOrigin.GENERATED, 1, 40);

        verify(projectAccess).requireOwned("u1", "p1");
        verify(repository).searchAssets(
                eq("u1"), eq(AssetScope.PROJECT), eq("p1"), nullable(String.class), nullable(String.class),
                nullable(AssetType.class), any(), eq(TagMode.ALL), eq(false), eq(true),
                eq(LibraryOrigin.GENERATED), eq(1), eq(40));
    }

    @Test
    void listDefaultsToNoGlobalAndNoOriginFilter() {
        when(repository.searchAssets(
                anyString(), any(), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(AssetType.class), any(), any(), eq(false), eq(false), nullable(LibraryOrigin.class),
                eq(1), eq(40)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 40));

        service.list("u1", AssetScope.PROJECT, "p1", null, null, null, null, TagMode.ALL, false,
                false, null, 1, 40);

        verify(repository).searchAssets(
                eq("u1"), eq(AssetScope.PROJECT), eq("p1"), nullable(String.class), nullable(String.class),
                nullable(AssetType.class), any(), eq(TagMode.ALL), eq(false), eq(false),
                nullable(LibraryOrigin.class), eq(1), eq(40));
    }

    @Test
    void globalAssetSkipsProjectOwnershipValidation() {
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));

        service.create("u1", new CreateAssetRequest(
                "u1", null, "IMAGE", null, "demo", null, "upload"), "idem");

        verify(projectAccess, never()).requireOwned(anyString(), anyString());
    }

    @Test
    void mediaRegistrationNoLongerQueuesAnalysis() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.upsertBlob(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn("b1");
        when(repository.upsertMedia(anyString(), anyString(), anyInt(), any(), any(Boolean.class), any()))
                .thenReturn("m1");
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn("a1");

        service.registerMedia("u1", new AssetCatalogService.MediaRegistration(
                "p1", AssetScope.PROJECT, AssetType.IMAGE, "image", null, "upload", null,
                "images/a.png", "a".repeat(64), "image/png", 10, 1, 1, null,
                MediaType.IMAGE, MediaRole.REFERENCE, true, null, 0), "idem");

        verify(repository, never()).requestAnalysisTask(anyString());
    }

    @Test
    void requestAnalysisQueuesImagesAndSkipsNonImages() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "doc-1")).thenReturn(Optional.of(asset("doc-1", AssetType.DOCUMENT)));

        var result = service.requestAnalysis("u1", List.of("a1", "doc-1", "missing"));

        assertThat(result.items().stream()
                .map(com.storyboard.asset.dto.ImportContracts.AnalysisRequestItem::assetId).toList())
                .containsExactly("a1");
        assertThat(result.skipped()).isEqualTo(2);
        verify(repository).requestAnalysisTask("a1");
        verify(repository, never()).requestAnalysisTask("doc-1");
    }

    @Test
    void requestFolderAnalysisQueuesEveryCandidateImage() {
        when(repository.listFolderAnalysisCandidates("u1", AssetScope.PROJECT, "p1", "f1", 500))
                .thenReturn(List.of(
                        new LibraryRepository.FolderScanAsset("a1", "视角 1"),
                        new LibraryRepository.FolderScanAsset("a2", "视角 2")));

        var result = service.requestFolderAnalysis("u1", AssetScope.PROJECT, "p1", "f1");

        assertThat(result.items().stream()
                .map(com.storyboard.asset.dto.ImportContracts.AnalysisRequestItem::assetId).toList())
                .containsExactly("a1", "a2");
        verify(projectAccess).requireOwned("u1", "p1");
        verify(repository).requestAnalysisTask("a1");
        verify(repository).requestAnalysisTask("a2");
    }

    @Test
    void purgeUnlinksProjectsAndMarksAssetPurged() {
        when(repository.findAsset("u1", "a1"))
                .thenReturn(Optional.of(asset("a1", com.storyboard.asset.client.AssetContracts.AssetStatus.TRASHED)));
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        service.purge("u1", "a1");

        verify(repository).unlinkAllProjects("a1");
        verify(repository).markPurgePending("a1");
        verify(repository).markPurged("a1");
    }

    @Test
    void purgeKeepsReferencedAssetBlocked() {
        when(repository.findAsset("u1", "a1"))
                .thenReturn(Optional.of(asset("a1", com.storyboard.asset.client.AssetContracts.AssetStatus.TRASHED)));
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThatThrownBy(() -> service.purge("u1", "a1"))
                .isInstanceOf(com.storyboard.common.BusinessException.class);

        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurgePending(anyString());
        verify(repository, never()).markPurged(anyString());
    }

    @Test
    void bulkPromoteMovesProjectAssetsToGlobalAndClearsFolderMappings() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "a2")).thenReturn(Optional.of(asset("a2")));
        when(repository.promote("u1", "a1", 1)).thenReturn(true);
        when(repository.promote("u1", "a2", 1)).thenReturn(true);

        var result = service.bulkPromote("u1", List.of("a1", "a2"));

        assertThat(result.updatedIds()).containsExactly("a1", "a2");
        verify(repository).removeAssetFolderMappings("u1", "a1");
        verify(repository).removeAssetFolderMappings("u1", "a2");
        verify(repository).unlinkAllProjects("a1");
        verify(repository).unlinkAllProjects("a2");
    }

    @Test
    void bulkTrashValidatesEveryAssetBeforeChangingAny() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bulkTrash("u1", List.of("a1", "missing")))
                .isInstanceOf(com.storyboard.common.BusinessException.class);

        verify(repository, never()).trash(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void duplicateCopiesAssetMediaTagsAndProjectLinks() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.duplicateAsset(any(), anyString(), anyString())).thenReturn("copy1");

        var result = service.duplicate("u1", new DuplicateAssetsRequest(
                "u1", List.of("a1"), null));

        assertThat(result.updatedIds()).containsExactly("copy1");
        verify(repository).copyMediaLinks("a1", "copy1");
        verify(repository).copyAssetTags("a1", "copy1");
        verify(repository).copyProjectLinks("a1", "copy1", "u1");
    }

    @Test
    void getMediaRefreshesSignedUrlForCurrentUser() {
        MediaUrlSigner signer = mock(MediaUrlSigner.class);
        when(signer.sign(eq("m1"), eq("u1"), any())).thenReturn("123.fresh");
        AssetCatalogServiceImpl catalog = new AssetCatalogServiceImpl(
                repository, projectAccess, signer, mock(FileAccessUrlSigner.class),
                new AssetPurgeService(repository),
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
        when(repository.findMedia("u1", "m1")).thenReturn(Optional.of(new MediaRef(
                "m1", "b1", MediaType.IMAGE, "images/a.png",
                "/api/library/media/m1/content", "images/a.png", "a.png", null,
                "image/png", 10, 1, 1, null, 0, MediaRole.REFERENCE, true, null)));

        MediaRef media = catalog.getMedia("u1", "m1");

        assertThat(media.contentUrl()).isEqualTo("/api/library/media/m1/content?token=123.fresh");
    }

    @Test
    void markNormalizesRatingAndClearsBlankFlags() {
        when(repository.updateMarks("u1", "a1", 5, null, null)).thenReturn(true);
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));

        AssetSummary marked = service.mark("u1", "a1", new AssetMarkRequest(5, " ", ""));

        assertThat(marked.id()).isEqualTo("a1");
        verify(repository).updateMarks("u1", "a1", 5, null, null);
    }

    @Test
    void bulkMarkReportsUpdatedAndSkippedAssets() {
        when(repository.updateMarks(eq("u1"), eq("a1"), eq(3), eq("pick"), nullable(String.class)))
                .thenReturn(true);
        when(repository.updateMarks(eq("u1"), eq("missing"), eq(3), eq("pick"), nullable(String.class)))
                .thenReturn(false);

        var result = service.bulkMark(
                "u1", new BulkMarkRequest(List.of("a1", "missing"), 3, "pick", null));

        assertThat(result.updatedIds()).containsExactly("a1");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().getFirst().assetId()).isEqualTo("missing");
    }

    @Test
    void getUsageReturnsRepositoryUsageForOwnedAsset() {
        AssetSummary asset = asset("a1");
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset));
        when(repository.findAssetUsage(asset)).thenReturn(new com.storyboard.asset.client.AssetContracts.AssetUsage(
                "a1", 2,
                List.of(new com.storyboard.asset.client.AssetContracts.UsageRef("s1", "分镜 1", "项目")),
                List.of(new com.storyboard.asset.client.AssetContracts.UsageRef("c1", "对话", null)),
                List.of(), List.of()));

        var usage = service.getUsage("u1", "a1");

        assertThat(usage.total()).isEqualTo(2);
        assertThat(usage.scenes()).hasSize(1);
        assertThat(usage.conversations().getFirst().title()).isEqualTo("对话");
    }

    @Test
    void listUnusedRequiresProjectOwnership() {
        when(repository.searchUnusedAssets("u1", "p1", 1, 40))
                .thenReturn(new PageResponse<>(List.of(asset("a1")), 1, 1, 40));

        var result = service.listUnused("u1", "p1", 1, 40);

        verify(projectAccess).requireOwned("u1", "p1");
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    void searchByTextUsesRepositoryRanking() {
        when(repository.searchAssetsByText("u1", "p1", "室内", null, null, 10))
                .thenReturn(List.of(asset("a1")));

        var results = service.search("u1", "p1", "室内", null, null, 10);

        verify(projectAccess).requireOwned("u1", "p1");
        assertThat(results).extracting(AssetSummary::id).containsExactly("a1");
    }

    @Test
    void searchBySimilarityRanksCandidatesInService() {
        AssetSummary source = namedAsset("source", "红色人物");
        AssetSummary close = namedAsset("close", "红色人物立绘");
        AssetSummary far = namedAsset("far", "蓝色风景");
        when(repository.findAsset("u1", "source")).thenReturn(Optional.of(source));
        when(repository.listSearchCandidates("u1", "p1", "IMAGE", "source", 400))
                .thenReturn(List.of(far, close));

        var results = service.search("u1", "p1", null, null, "source", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().id()).isEqualTo("close");
    }

    @Test
    void searchByColorRanksNearestFirst() {
        when(repository.listColorCandidates("u1", "p1", 500)).thenReturn(List.of(
                new com.storyboard.asset.repository.LibraryRepository.ColorCandidate(
                        asset("red"), "#ff0000"),
                new com.storyboard.asset.repository.LibraryRepository.ColorCandidate(
                        asset("blue"), "#0000ff")));

        var results = service.search("u1", "p1", null, "#fe0000", null, 10);

        assertThat(results).extracting(AssetSummary::id).containsExactly("red", "blue");
    }

    private static AssetSummary namedAsset(String id, String name) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", AssetType.IMAGE, null,
                name, null, null, false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE,
                com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static AssetSummary asset(String id) {
        return asset(id, com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE);
    }

    private static AssetSummary asset(String id, AssetType type) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", type, null,
                "demo", null, null, false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE,
                com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static AssetSummary asset(
            String id,
            com.storyboard.asset.client.AssetContracts.AssetStatus status
    ) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", AssetType.IMAGE, null,
                "demo", null, null, false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE, status,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
