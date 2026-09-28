package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.CreateFolderRequest;
import com.storyboard.asset.client.AssetContracts.CopyFoldersRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MoveAssetsToFolderRequest;
import com.storyboard.asset.client.AssetContracts.MoveFolderRequest;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryFolderServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private AssetCatalogService catalogService;
    private LibraryFolderServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        catalogService = mock(AssetCatalogService.class);
        service = new LibraryFolderServiceImpl(
                repository, projectAccess, catalogService,
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
    }

    @Test
    void createsAProjectFolderWithANormalizedName() {
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色"
        )).thenReturn(Optional.empty());
        when(repository.insertFolder("u1", AssetScope.PROJECT, "p1", null, "角色", "角色"))
                .thenReturn("folder-1");
        when(repository.findFolder("u1", "folder-1")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-1", "u1", AssetScope.PROJECT, "p1", null,
                        "角色", "角色", 0, 1
                )
        ));

        var folder = service.create("u1", new CreateFolderRequest(
                "u1", AssetScope.PROJECT, "p1", null, "  角色  "
        ));

        assertThat(folder.id()).isEqualTo("folder-1");
        assertThat(folder.name()).isEqualTo("角色");
        verify(projectAccess).requireOwned("u1", "p1");
    }

    @Test
    void createsANumberedFolderWhenTheNameAlreadyExists() {
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色"
        )).thenReturn(Optional.of(new LibraryRepository.FolderRow(
                "folder-existing", "u1", AssetScope.PROJECT, "p1", null,
                "角色", "角色", 0, 1
        )));
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色（1）"
        )).thenReturn(Optional.empty());
        when(repository.insertFolder(
                "u1", AssetScope.PROJECT, "p1", null, "角色（1）", "角色（1）"
        )).thenReturn("folder-2");
        when(repository.findFolder("u1", "folder-2")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-2", "u1", AssetScope.PROJECT, "p1", null,
                        "角色（1）", "角色（1）", 0, 1
                )
        ));

        var folder = service.create("u1", new CreateFolderRequest(
                "u1", AssetScope.PROJECT, "p1", null, "角色"
        ));

        assertThat(folder.name()).isEqualTo("角色（1）");
    }

    @Test
    void incrementsTheNumberedFolderSuffixUntilTheNameIsAvailable() {
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色"
        )).thenReturn(Optional.of(new LibraryRepository.FolderRow(
                "folder-1", "u1", AssetScope.PROJECT, "p1", null,
                "角色", "角色", 0, 1
        )));
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色（1）"
        )).thenReturn(Optional.of(new LibraryRepository.FolderRow(
                "folder-2", "u1", AssetScope.PROJECT, "p1", null,
                "角色（1）", "角色（1）", 0, 1
        )));
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色（2）"
        )).thenReturn(Optional.empty());
        when(repository.insertFolder(
                "u1", AssetScope.PROJECT, "p1", null, "角色（2）", "角色（2）"
        )).thenReturn("folder-3");
        when(repository.findFolder("u1", "folder-3")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-3", "u1", AssetScope.PROJECT, "p1", null,
                        "角色（2）", "角色（2）", 0, 1
                )
        ));

        var folder = service.create("u1", new CreateFolderRequest(
                "u1", AssetScope.PROJECT, "p1", null, "角色"
        ));

        assertThat(folder.name()).isEqualTo("角色（2）");
    }

    @Test
    void rejectsMovingAssetsIntoAFolderFromAnotherProject() {
        when(repository.findFolder("u1", "folder-1")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-1", "u1", AssetScope.PROJECT, "p2", null,
                        "角色", "角色", 0, 1
                )
        ));

        assertThatThrownBy(() -> service.moveAssets("u1", new MoveAssetsToFolderRequest(
                "u1", AssetScope.PROJECT, "p1", "folder-1", List.of("asset-1")
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录不存在");
    }

    @Test
    void deletesNestedFoldersAfterMovingContainedAssetsToTrash() {
        when(repository.findFolder("u1", "folder-1")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-1", "u1", AssetScope.PROJECT, "p1", null,
                        "角色", "角色", 0, 4
                )
        ));
        when(repository.listFolderTreeIds("u1", "folder-1"))
                .thenReturn(List.of("folder-child", "folder-1"));

        service.delete("u1", "folder-1", 4);

        verify(repository).trashAssetsInFolders(
                "u1", AssetScope.PROJECT, "p1", List.of("folder-child", "folder-1"), 3);
        verify(repository).deleteAssetFolderMappingsForFolders(
                "u1", List.of("folder-child", "folder-1"));
        verify(repository).deleteFolderRow("u1", "folder-child");
        verify(repository).deleteFolderRow("u1", "folder-1");
    }

    @Test
    void movesFolderIntoAnotherFolder() {
        when(repository.findFolder("u1", "folder-1")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-1", "u1", AssetScope.PROJECT, "p1", null,
                        "角色", "角色", 0, 3
                )
        ));
        when(repository.findFolder("u1", "folder-2")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-2", "u1", AssetScope.PROJECT, "p1", null,
                        "参考", "参考", 0, 1
                )
        ));
        when(repository.listFolderTreeIds("u1", "folder-1")).thenReturn(List.of("folder-1"));
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", "folder-2", "角色"
        )).thenReturn(Optional.empty());
        when(repository.moveFolder("u1", "folder-1", "folder-2", 3)).thenReturn(true);

        service.moveFolder("u1", "folder-1", new MoveFolderRequest("u1", "folder-2", 3L));

        verify(repository).moveFolder("u1", "folder-1", "folder-2", 3);
    }

    @Test
    void copiesFolderTreeAndContainedAssets() {
        when(repository.findFolder("u1", "folder-1")).thenReturn(Optional.of(
                new LibraryRepository.FolderRow(
                        "folder-1", "u1", AssetScope.PROJECT, "p1", null,
                        "角色", "角色", 0, 1
                )
        ));
        when(repository.findFolderBySibling(
                "u1", AssetScope.PROJECT, "p1", null, "角色（1）"
        )).thenReturn(Optional.empty());
        when(repository.insertFolder(
                "u1", AssetScope.PROJECT, "p1", null, "角色（1）", "角色（1）"
        )).thenReturn("folder-copy");
        when(repository.listDirectFolderAssetIds("u1", "folder-1"))
                .thenReturn(List.of("asset-1"));
        when(repository.listChildFolders("u1", "folder-1")).thenReturn(List.of());

        var result = service.copyFolders("u1", new CopyFoldersRequest(
                "u1", List.of("folder-1"), null));

        assertThat(result.updatedIds()).containsExactly("folder-copy");
        verify(catalogService).duplicate(
                "u1", new DuplicateAssetsRequest("u1", List.of("asset-1"), "folder-copy"));
    }
}
