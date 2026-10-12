package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.config.AssetProperties;
import com.storyboard.asset.dto.ImportContracts;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.GroupService;
import com.storyboard.asset.service.LibraryFolderService;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportServiceImplTest {

    private LibraryRepository repository;
    private ObjectStorage storage;
    private MediaUploadService uploadService;
    private AssetCatalogService catalogService;
    private GroupService groupService;
    private LibraryFolderService folderService;
    private ImportServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        storage = mock(ObjectStorage.class);
        uploadService = mock(MediaUploadService.class);
        catalogService = mock(AssetCatalogService.class);
        groupService = mock(GroupService.class);
        folderService = mock(LibraryFolderService.class);
        service = new ImportServiceImpl(
                repository,
                mock(ProjectAccessPort.class),
                uploadService,
                catalogService,
                groupService,
                folderService,
                storage,
                new AssetProperties("", "", "", "secret")
        );
    }

    @Test
    void folderGroupImportRequiresAProject() {
        assertThatThrownBy(() -> service.scan(
                "u1",
                new ImportContracts.ScanRequest(
                        null,
                        AssetScope.GLOBAL,
                        ImportContracts.ImportMode.GROUP,
                        "角色集",
                        List.of(new ImportContracts.Entry(
                                "item-1", "角色集/01.png", "01.png", 12, "image/png"))
                )
        )).isInstanceOf(com.storyboard.common.BusinessException.class)
                .hasMessageContaining("选择项目");
    }

    @Test
    void duplicateImportRepairsMissingObjectAndCoverWithoutQueuingAnalysis() throws Exception {
        byte[] bytes = pngBytes();
        when(repository.findImportJob("u1", "job-1")).thenReturn(Optional.of(importJob()));
        when(repository.findImportItem("job-1", "item-1")).thenReturn(importItem());
        when(repository.findBlobBySha(any())).thenReturn(Optional.of(blobRow()));
        when(repository.findAssetIdByBlobAndOwner("u1", "blob-1")).thenReturn(Optional.of("a1"));
        when(storage.exists("images/original.png")).thenReturn(false);
        AssetSummary asset = asset("a1");
        when(catalogService.get("u1", "a1")).thenReturn(new AssetDetail(asset, List.of(), List.of()));

        service.uploadItem(
                "u1",
                "job-1",
                "item-1",
                "token",
                new MockMultipartFile("file", "photo.png", "image/png", bytes)
        );

        verify(storage).put(
                eq("images/original.png"),
                any(ByteArrayInputStream.class),
                eq((long) bytes.length),
                eq("image/png")
        );
        verify(repository).updateCover("a1", "/api/files/images/original.png");
        verify(repository, never()).requestAnalysisTask(anyString());
        verify(repository).finishImportItem(
                "item-db-1", "DUPLICATE_LINKED", "a1", LibraryRepository.sha256Hex(bytes), null);
    }

    @Test
    void treeImportLinksUploadedAssetToItsNestedFolder() throws Exception {
        byte[] bytes = pngBytes();
        LibraryRepository.ImportJob job = importJob(
                ImportContracts.ImportMode.TREE, "p1", "角色/正面/01.png");
        LibraryRepository.ImportItem item = importItem("角色/正面/01.png");
        AssetSummary asset = asset("a-tree");
        when(repository.findImportJob("u1", "job-1")).thenReturn(Optional.of(job));
        when(repository.findImportItem("job-1", "item-1")).thenReturn(item);
        when(repository.findBlobBySha(any())).thenReturn(Optional.empty());
        when(folderService.ensurePath("u1", AssetScope.PROJECT, "p1", List.of("角色", "正面")))
                .thenReturn("folder-1");
        when(uploadService.uploadDirect(
                eq("u1"), eq("p1"), eq(AssetScope.PROJECT), eq("01.png"), eq(null),
                eq("角色/正面/01.png"), any(), eq("import:job-1:item-1")))
                .thenReturn(asset);
        when(catalogService.get("u1", "a-tree")).thenReturn(new AssetDetail(asset, List.of(), List.of()));

        service.uploadItem(
                "u1", "job-1", "item-1", "token",
                new MockMultipartFile("file", "01.png", "image/png", bytes));

        verify(folderService).ensurePath("u1", AssetScope.PROJECT, "p1", List.of("角色", "正面"));
        verify(repository).assignAssetsToFolder(
                "u1", AssetScope.PROJECT, "p1", "folder-1", List.of("a-tree"));
    }

    @Test
    void treeScanAcceptsDocumentsAndUnknownFiles() {
        ImportContracts.ScanResponse response = service.scan("u1", new ImportContracts.ScanRequest(
                "p1",
                AssetScope.PROJECT,
                ImportContracts.ImportMode.TREE,
                null,
                List.of(
                        new ImportContracts.Entry("i1", "角色/01.png", "01.png", 12, "image/png"),
                        new ImportContracts.Entry("i2", "资料/说明.md", "说明.md", 2048, "text/markdown"),
                        new ImportContracts.Entry("i3", "资料/打包.zip", "打包.zip", 4096, "application/zip")
                )
        ));

        assertThat(response.fileCount()).isEqualTo(3);
        assertThat(response.ignoredCount()).isZero();
    }

    @Test
    void groupScanKeepsImagesOnly() {
        ImportContracts.ScanResponse response = service.scan("u1", new ImportContracts.ScanRequest(
                "p1",
                AssetScope.PROJECT,
                ImportContracts.ImportMode.GROUP,
                "角色集",
                List.of(
                        new ImportContracts.Entry("i1", "角色/01.png", "01.png", 12, "image/png"),
                        new ImportContracts.Entry("i2", "资料/说明.md", "说明.md", 2048, "text/markdown")
                )
        ));

        assertThat(response.fileCount()).isEqualTo(1);
        assertThat(response.ignoredCount()).isEqualTo(1);
    }

    private static LibraryRepository.ImportJob importJob() {
        return importJob(ImportContracts.ImportMode.ASSETS, null, "photo.png");
    }

    private static LibraryRepository.ImportJob importJob(
            ImportContracts.ImportMode mode,
            String projectId,
            String relativePath
    ) {
        return new LibraryRepository.ImportJob(
                "job-1",
                "u1",
                projectId,
                projectId == null ? AssetScope.GLOBAL : AssetScope.PROJECT,
                mode,
                null,
                "manifest",
                "token",
                OffsetDateTime.now().plusMinutes(5),
                "PENDING",
                1,
                12,
                0,
                0,
                0,
                0,
                0,
                OffsetDateTime.now()
        );
    }

    private static LibraryRepository.ImportItem importItem() {
        return importItem("photo.png");
    }

    private static LibraryRepository.ImportItem importItem(String relativePath) {
        return new LibraryRepository.ImportItem(
                "item-db-1",
                "job-1",
                "item-1",
                relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                12,
                "image/png",
                null,
                "PENDING",
                null,
                null,
                0,
                0
        );
    }

    private static LibraryRepository.BlobRow blobRow() {
        return new LibraryRepository.BlobRow(
                "blob-1",
                "images/original.png",
                "sha",
                "image/png",
                12,
                null,
                null,
                null,
                MediaType.IMAGE
        );
    }

    private static AssetSummary asset(String id) {
        return new AssetSummary(
                id,
                "u1",
                AssetScope.GLOBAL,
                null,
                AssetType.IMAGE,
                null,
                "photo",
                null,
                null,
                false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE,
                AssetStatus.ACTIVE,
                null,
                1,
                List.of(),
                List.of(),
                "upload",
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static byte[] pngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
        };
    }
}
