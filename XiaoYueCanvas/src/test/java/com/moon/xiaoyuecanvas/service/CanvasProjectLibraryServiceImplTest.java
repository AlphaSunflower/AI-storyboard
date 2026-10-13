package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasProjectLibraryServiceImpl;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetGroupDetail;
import com.storyboard.asset.client.AssetContracts.AssetGroupMediaItem;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasProjectLibraryServiceImplTest {

    private static final FileUrlSigner SIGNER = new FileUrlSigner("test-secret", 3600);

    /** 去掉签名 query，便于断言 objectUrl 的路径部分。 */
    private static String unsigned(String url) {
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(0, query) : url;
    }

    @Test
    void importGroupCreatesOneCanvasAssetForEveryGroupMedia() {
        AssetClient assetClient = mock(AssetClient.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        CanvasAssetMapper canvasAssetMapper = mock(CanvasAssetMapper.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        when(assetClient.getGroupInternal("group-1", "user-1")).thenReturn(new AssetGroupDetail(
                "group-1",
                "主角集",
                null,
                1,
                List.of(
                        groupItem("asset-image", "media-image", MediaType.IMAGE,
                                "images/reference.png", "image/png"),
                        groupItem("asset-video", "media-video", MediaType.VIDEO,
                                "videos/reference.mp4", "video/mp4")
                )
        ));
        when(canvasAssetMapper.selectOne(any())).thenReturn(null);

        CanvasProjectLibraryService service = new CanvasProjectLibraryServiceImpl(
                assetClient, canvasService, canvasAssetMapper, SIGNER);
        List<CanvasAsset> imported = service.importGroup("user-1", "canvas-1", "group-1", "node-1");

        assertEquals(List.of("image", "video"), imported.stream().map(CanvasAsset::getType).toList());
        assertEquals(List.of("asset-image", "asset-video"),
                imported.stream().map(CanvasAsset::getLibraryAssetId).toList());
        assertEquals(List.of("/api/files/images/reference.png", "/api/files/videos/reference.mp4"),
                imported.stream().map(CanvasAsset::getObjectUrl).map(
                        CanvasProjectLibraryServiceImplTest::unsigned).toList());
        assertTrue(imported.stream().allMatch(asset -> asset.getObjectUrl().contains("?token=")));
        ArgumentCaptor<CanvasAsset> inserted = ArgumentCaptor.forClass(CanvasAsset.class);
        verify(canvasAssetMapper, times(2)).insert(inserted.capture());
        assertEquals("node-1", inserted.getAllValues().getFirst().getProducerNodeId());
    }

    @Test
    void importAssetReusesExistingCanvasAssetWhenTheMinioObjectAlreadyExists() {
        AssetClient assetClient = mock(AssetClient.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        CanvasAssetMapper canvasAssetMapper = mock(CanvasAssetMapper.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        when(assetClient.getAssetInternal("library-alias-2", "user-1")).thenReturn(new AssetDetail(
                libraryAsset("library-alias-2", "source-alias-2"),
                List.of(media("media-2", MediaType.IMAGE,
                        "images/d121df03-61ef-4664-b0dc-0dd9663d028f.jpeg", "image/jpeg")),
                List.of()
        ));
        CanvasAsset existing = new CanvasAsset();
        existing.setId("canvas-existing");
        existing.setObjectUrl("/api/files/images/d121df03-61ef-4664-b0dc-0dd9663d028f.jpeg");
        when(canvasAssetMapper.selectOne(any())).thenReturn(null, existing);

        CanvasProjectLibraryService service = new CanvasProjectLibraryServiceImpl(
                assetClient, canvasService, canvasAssetMapper, SIGNER);
        CanvasAsset imported = service.importAsset("user-1", "canvas-1", "library-alias-2", "node-1");

        assertEquals(existing, imported);
        assertTrue(imported.getObjectUrl().contains("?token="));
        verify(canvasAssetMapper, never()).insert(any(CanvasAsset.class));
    }

    @Test
    void importMediaResolvesLibraryMediaByMediaIdWithoutSignedUrl() {
        AssetClient assetClient = mock(AssetClient.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        CanvasAssetMapper canvasAssetMapper = mock(CanvasAssetMapper.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        when(assetClient.getMediaInternal("media-1", "user-1"))
                .thenReturn(media("media-1", MediaType.IMAGE,
                        "images/library-1.png", "image/png"));
        when(canvasAssetMapper.selectOne(any())).thenReturn(null);

        CanvasProjectLibraryService service = new CanvasProjectLibraryServiceImpl(
                assetClient, canvasService, canvasAssetMapper, SIGNER);
        CanvasAsset imported = service.importMedia("user-1", "canvas-1", "media-1", "node-1");

        assertEquals("/api/files/images/library-1.png", unsigned(imported.getObjectUrl()));
        assertTrue(imported.getObjectUrl().contains("?token="));
        assertEquals("images/library-1.png", imported.getObjectKey());
        assertEquals("media-1", imported.getLibraryAssetId());
        assertEquals("node-1", imported.getProducerNodeId());
        verify(canvasAssetMapper).insert(imported);
    }

    private static AssetGroupMediaItem groupItem(
            String assetId,
            String mediaId,
            MediaType type,
            String objectKey,
            String mimeType
    ) {
        return new AssetGroupMediaItem(
                new MediaRef(
                        mediaId, "blob-" + mediaId, type,
                        "/api/files/" + objectKey,
                        "/api/library/media/" + mediaId + "/content",
                        objectKey,
                        objectKey.substring(objectKey.lastIndexOf('/') + 1),
                        null,
                        mimeType,
                        128,
                        640,
                        480,
                        type == MediaType.VIDEO ? 3.0 : null,
                        0,
                        MediaRole.REFERENCE,
                        true,
                        null
                ),
                assetId,
                objectKey,
                null,
                1,
                List.of()
        );
    }

    private static MediaRef media(
            String mediaId,
            MediaType type,
            String objectKey,
            String mimeType
    ) {
        return new MediaRef(
                mediaId, "blob-" + mediaId, type,
                "/api/files/" + objectKey,
                "/api/library/media/" + mediaId + "/content",
                objectKey,
                objectKey.substring(objectKey.lastIndexOf('/') + 1),
                null,
                mimeType,
                128,
                640,
                480,
                null,
                0,
                MediaRole.REFERENCE,
                true,
                null
        );
    }

    private static AssetSummary libraryAsset(String assetId, String sourceId) {
        return new AssetSummary(
                assetId, "user-1", AssetScope.PROJECT, "project-1", AssetType.IMAGE, null,
                "d121.jpeg", null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                "/api/files/images/d121df03-61ef-4664-b0dc-0dd9663d028f.jpeg",
                1, List.of(), List.of(), "generation", sourceId, null, null
        );
    }
}
