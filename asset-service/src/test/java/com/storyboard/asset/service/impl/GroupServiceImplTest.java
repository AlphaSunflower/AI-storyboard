package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateGroupFromAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import com.storyboard.asset.domain.GroupSnapshotExpander;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private GroupServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        service = new GroupServiceImpl(repository, projectAccess, mock(MediaUrlSigner.class));
    }

    @Test
    void createsOneGroupFromSelectedPrimaryMediaInOrder() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "a2")).thenReturn(Optional.of(asset("a2")));
        when(repository.findPrimaryMediaId("a1")).thenReturn(Optional.of("m1"));
        when(repository.findPrimaryMediaId("a2")).thenReturn(Optional.of("m2"));
        when(repository.insertAsset(
                anyString(), any(), anyString(), any(), any(), anyString(), any(), anyString(), any()))
                .thenReturn("group-1");
        when(repository.findAsset("u1", "group-1")).thenReturn(Optional.of(asset("group-1")));
        var group = service.createFromAssets("u1", new CreateGroupFromAssetsRequest(
                "u1", "p1", "角色合集", List.of("a1", "a2")
        ), "idem");

        assertThat(group.id()).isEqualTo("group-1");
        verify(projectAccess).requireOwned("u1", "p1");
        verify(repository).linkGroupItem("group-1", "m1", "a1", 0);
        verify(repository).linkGroupItem("group-1", "m2", "a2", 1);
    }

    @Test
    void rejectsAssetsFromAnotherProject() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1", "p2")));

        assertThatThrownBy(() -> service.createFromAssets("u1", new CreateGroupFromAssetsRequest(
                "u1", "p1", "角色合集", List.of("a1")
        ), "idem")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一项目");

        verify(repository, never()).insertAsset(
                anyString(), any(), anyString(), any(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    void rejectsFoldersOrAssetGroupsAsGroupMembers() {
        AssetSummary groupAsset = new AssetSummary(
                "g1", "u1", AssetScope.PROJECT, "p1", AssetType.GROUP, null,
                "group", null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                null, 1, List.of(), List.of(), "group", null,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(repository.findAsset("u1", "g1")).thenReturn(Optional.of(groupAsset));

        assertThatThrownBy(() -> service.createFromAssets("u1", new CreateGroupFromAssetsRequest(
                "u1", "p1", "错误合集", List.of("g1")
        ), "idem")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许图片");
    }

    @Test
    void groupDetailIncludesFullMemberAssetsForTree() {
        LibraryRepository.GroupRow group = new LibraryRepository.GroupRow(
                "g1", "g1", "u1", AssetScope.PROJECT, "p1", 1, "角色合集", null
        );
        AssetSummary member = asset("a1");
        when(repository.findGroup("u1", "g1")).thenReturn(Optional.of(group));
        when(repository.listGroupItems("u1", "g1")).thenReturn(List.of(
                new GroupSnapshotExpander.Item(
                        "m1", "a1", "b1", "object/a1.png", "IMAGE", "REFERENCE", 0
                )
        ));
        when(repository.findMedia("u1", "m1")).thenReturn(Optional.of(new MediaRef(
                "m1", "b1", MediaType.IMAGE, "/files/images/a1.png", "/api/files/images/a1.png",
                "object/a1.png", "a1.png", null, "image/png", 1024, 512, 512,
                null, 0, MediaRole.REFERENCE, true, null
        )));
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(member));

        var detail = service.get("u1", "g1");

        assertThat(detail.media()).hasSize(1);
        assertThat(detail.media().getFirst().asset()).isEqualTo(member);
    }

    private static AssetSummary asset(String id) {
        return asset(id, "p1");
    }

    private static AssetSummary asset(String id, String projectId) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, projectId, AssetType.IMAGE, null,
                id, null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
