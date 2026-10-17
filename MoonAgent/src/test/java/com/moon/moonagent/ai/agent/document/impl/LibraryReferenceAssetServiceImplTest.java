package com.moon.moonagent.ai.agent.document.impl;

import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.storage.MinioStorage;
import com.moon.moonagent.support.MyBatisPlusLambdaCache;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.ProjectLinkRef;
import com.storyboard.asset.client.AssetServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LibraryReferenceAssetServiceImpl} 的聚焦单测。
 *
 * <p>覆盖验收口径：归属校验失败不下载、正常路径落库并返回
 * {@code /api/files/files/} 形态附件、同一引用第二次调用不重复创建、
 * 下载/存储异常只降级返回 {@code null} 不向上抛，以及
 * <b>向 asset-service 必须传真实 userId（而非 null）</b>这条静默失效回归。
 */
class LibraryReferenceAssetServiceImplTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String USER_ID = "user-1";
    private static final String PROJECT_ID = "project-1";
    private static final String ASSET_ID = "library-asset-1";
    private static final String MEDIA_ID = "library-media-1";
    private static final String OBJECT_KEY = "documents/设定.docx";
    private static final String SAVED_URL = "/api/files/files/uuid-设定.docx";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final byte[] CONTENT = "第一章 设定".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void installLambdaCache() {
        // LambdaQueryWrapper 在纯 Mockito 环境下需要 MyBatis-Plus 的列名缓存。
        MyBatisPlusLambdaCache.install(AgentAssetMapper.class);
    }

    /**
     * 回归「静默失效」：asset-client 只在 actingUserId 非空时才带 {@code X-User-Id}，
     * 缺它会退化成以 {@code "internal"} 身份查询 {@code owner_user_id}，永远查不到资产。
     */
    @Test
    void passesTheRealUserIdToAssetServiceInsteadOfNull() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        m.stubEmptyAssetTable();
        m.stubTransfer();

        assertThat(ensure(m)).isNotNull();

        verify(m.assetClient).getAssetInternal(ASSET_ID, USER_ID);
        verify(m.assetClient).getMediaInternal(MEDIA_ID, USER_ID);
        verify(m.assetClient, never()).getAssetInternal(any(), isNull());
        verify(m.assetClient, never()).getMediaInternal(any(), isNull());
    }

    /** 归属校验失败（资产属于另一个项目）→ 返回 null，且绝不下载、绝不上传。 */
    @Test
    void rejectsMediaThatDoesNotBelongToProjectWithoutDownloading() {
        Mocks m = new Mocks();
        // 本人资产，但 scope=PROJECT 且只挂在 project-other 上。
        when(m.assetClient.getAssetInternal(eq(ASSET_ID), any())).thenReturn(new AssetDetail(
                asset(AssetScope.PROJECT, "project-other", null),
                List.of(media(MEDIA_ID, "设定.docx")),
                List.of()));

        assertThat(ensure(m)).isNull();

        verify(m.assetClient, never()).getMediaInternal(any(), any());
        verify(m.minioStorage, never()).get(any());
        verify(m.storageService, never()).saveGeneratedFile(any(), any(), any());
        verify(m.assetMapper, never()).insert(any(AgentAsset.class));
    }

    /** mediaId 不在该资产下 → 归属校验不通过，同样不下载、不落库。 */
    @Test
    void rejectsMediaIdThatIsNotPartOfTheAsset() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media("other-media", "别的.docx")));

        assertThat(ensure(m)).isNull();

        verify(m.assetClient, never()).getMediaInternal(any(), any());
        verify(m.minioStorage, never()).get(any());
        verify(m.storageService, never()).saveGeneratedFile(any(), any(), any());
        verify(m.assetMapper, never()).insert(any(AgentAsset.class));
    }

    /**
     * 本人 {@code scope=GLOBAL} 素材即使没有任何项目链接也必须放行：
     * 前端从不调用 linkProject，全局素材永远不会出现在 linkedProjects 里。
     */
    @Test
    void allowsOwnGlobalAssetEvenWithoutProjectLink() {
        Mocks m = new Mocks();
        when(m.assetClient.getAssetInternal(eq(ASSET_ID), any())).thenReturn(new AssetDetail(
                asset(AssetScope.GLOBAL, null, null),
                List.of(media(MEDIA_ID, "设定.docx")),
                List.of()));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        m.stubEmptyAssetTable();
        m.stubTransfer();

        AgentAttachmentRequest result = ensure(m);

        assertThat(result).isNotNull();
        assertThat(result.url()).startsWith("/api/files/files/");
        verify(m.assetMapper).insert(any(AgentAsset.class));
    }

    /** asset-service 判定资产不存在/不属于本人（404）→ 降级返回 null，且不碰对象存储。 */
    @Test
    void degradesToNullWhenAssetServiceRejectsOwnership() {
        Mocks m = new Mocks();
        when(m.assetClient.getAssetInternal(eq(ASSET_ID), any()))
                .thenThrow(new AssetServiceException(40401, 404, "资产不存在", "corr-1"));

        assertThatCode(() -> assertThat(ensure(m)).isNull()).doesNotThrowAnyException();

        verify(m.assetClient, never()).getMediaInternal(any(), any());
        verify(m.minioStorage, never()).get(any());
        verify(m.storageService, never()).saveGeneratedFile(any(), any(), any());
        verify(m.assetMapper, never()).insert(any(AgentAsset.class));
    }

    /** 正常路径 → 取字节、写 MinIO、落库 agent_assets，并返回 /api/files/files/ 形态附件。 */
    @Test
    void transfersLibraryFileIntoConversationReferenceAsset() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        m.stubEmptyAssetTable();
        m.stubTransfer();

        AgentAttachmentRequest result = ensure(m);

        assertThat(result).isNotNull();
        assertThat(result.url()).isEqualTo(SAVED_URL);
        assertThat(result.url()).startsWith("/api/files/files/");
        assertThat(result.kind()).isEqualTo("file");
        assertThat(result.name()).isEqualTo("设定.docx");
        assertThat(result.mimeType()).isEqualTo(DOCX_MIME);
        assertThat(result.assetId()).isNotBlank();

        ArgumentCaptor<AgentAsset> inserted = ArgumentCaptor.forClass(AgentAsset.class);
        verify(m.assetMapper).insert(inserted.capture());
        AgentAsset row = inserted.getValue();
        assertThat(row.getId()).isEqualTo(result.assetId());
        assertThat(row.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(row.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(row.getType()).isEqualTo("reference");
        assertThat(row.getKind()).isEqualTo("file");
        assertThat(row.getUrl()).isEqualTo(SAVED_URL);
        assertThat(row.getOriginalName()).isEqualTo("设定.docx");
        assertThat(row.getMimeType()).isEqualTo(DOCX_MIME);
        assertThat(row.getIdempotencyKey()).isNotBlank();
        assertThat(row.getRequestFingerprint()).hasSize(64);
    }

    /** 幂等：同一会话 + 同一 mediaId 第二次引用直接复用既有资产，不重复创建。 */
    @Test
    void reusesExistingConversationAssetForRepeatedReference() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        m.stubTransfer();

        AgentAsset existing = new AgentAsset();
        existing.setId("existing-asset-9");
        existing.setConversationId(CONVERSATION_ID);
        existing.setType("reference");
        existing.setKind("file");
        existing.setUrl(SAVED_URL);
        existing.setOriginalName("设定.docx");
        existing.setMimeType(DOCX_MIME);
        // 「库里出现这一行」只在 insert 成功之后成立：首次两段判空查询都查不到，落库后才会命中。
        m.stubAssetRowAppearsAfterInsert(existing);

        AgentAttachmentRequest first = ensure(m);
        AgentAttachmentRequest second = ensure(m);

        assertThat(first).as("首次引用应新建会话资产").isNotNull();
        assertThat(second).as("第二次引用应复用既有会话资产").isNotNull();
        assertThat(second.assetId()).isEqualTo("existing-asset-9");
        assertThat(second.url()).isEqualTo(SAVED_URL);
        assertThat(second.kind()).isEqualTo("file");
        // 只落盘一次、只落库一次。
        verify(m.storageService, times(1)).saveGeneratedFile(any(), any(), any());
        verify(m.assetMapper, times(1)).insert(any(AgentAsset.class));
    }

    /** 幂等键稳定：两次调用的判空查询条件（含去重键）逐字一致。 */
    @Test
    void keepsIdempotencyKeyStableAcrossRepeatedCalls() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        m.stubEmptyAssetTable();
        m.stubTransfer();

        ensure(m);
        ensure(m);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> queries =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(m.assetMapper, times(4)).selectOne(queries.capture());
        var firstKeyQuery =
                (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>)
                        queries.getAllValues().get(0);
        var secondKeyQuery =
                (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>)
                        queries.getAllValues().get(2);
        assertThat(secondKeyQuery.getSqlSegment()).isEqualTo(firstKeyQuery.getSqlSegment());
        assertThat(secondKeyQuery.getParamNameValuePairs())
                .isEqualTo(firstKeyQuery.getParamNameValuePairs());
    }

    /** 下载抛异常 → 返回 null 且不向上抛。 */
    @Test
    void degradesToNullWhenByteTransferFails() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        when(m.minioStorage.get(OBJECT_KEY)).thenThrow(new RuntimeException("MinIO 不可用"));

        assertThatCode(() -> assertThat(ensure(m)).isNull()).doesNotThrowAnyException();

        verify(m.storageService, never()).saveGeneratedFile(any(), any(), any());
        verify(m.assetMapper, never()).insert(any(AgentAsset.class));
    }

    /** 存储抛异常 → 同样只降级返回 null。 */
    @Test
    void degradesToNullWhenStorageFails() {
        Mocks m = new Mocks();
        m.stubOwnedAsset(List.of(media(MEDIA_ID, "设定.docx")));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID))
                .thenReturn(media(MEDIA_ID, "设定.docx"));
        when(m.minioStorage.get(OBJECT_KEY)).thenReturn(CONTENT);
        m.stubEmptyAssetTable();
        when(m.storageService.saveGeneratedFile(any(), any(), any()))
                .thenThrow(new RuntimeException("MinIO 写入失败"));

        assertThatCode(() -> assertThat(ensure(m)).isNull()).doesNotThrowAnyException();

        verify(m.assetMapper, never()).insert(any(AgentAsset.class));
    }

    /** 超过可配置上限 → 拒绝并记日志，不下载。 */
    @Test
    void rejectsOversizedLibraryFile() {
        Mocks m = new Mocks();
        MediaRef big = new MediaRef(MEDIA_ID, "blob-1", MediaType.DOCUMENT,
                "/api/files/" + OBJECT_KEY, "/api/library/media/" + MEDIA_ID + "/content",
                OBJECT_KEY, "设定.docx", null, DOCX_MIME, 40L * 1024 * 1024,
                null, null, null, 0, MediaRole.REFERENCE, true, null);
        when(m.assetClient.getAssetInternal(eq(ASSET_ID), any())).thenReturn(new AssetDetail(
                asset(AssetScope.PROJECT, PROJECT_ID, null), List.of(big), List.of()));
        when(m.assetClient.getMediaInternal(MEDIA_ID, USER_ID)).thenReturn(big);

        assertThat(ensure(m)).isNull();

        verify(m.minioStorage, never()).get(any());
        verify(m.storageService, never()).saveGeneratedFile(any(), any(), any());
    }

    /** 非 library_file 引用不由本服务处理，直接跳过。 */
    @Test
    void ignoresNonLibraryReferences() {
        Mocks m = new Mocks();
        AgentReferenceRequest localFile = new AgentReferenceRequest(
                "local_file", "asset-1", "@本地上传.docx", "本地上传.docx",
                "asset-1", DOCX_MIME, "/api/files/files/x.docx", DOCX_MIME, 12L, null);

        assertThat(m.service.ensureConversationAsset(
                CONVERSATION_ID, USER_ID, PROJECT_ID, localFile)).isNull();
        verify(m.assetClient, never()).getAssetInternal(any(), any());
    }

    private static AgentAttachmentRequest ensure(Mocks m) {
        return m.service.ensureConversationAsset(
                CONVERSATION_ID, USER_ID, PROJECT_ID, libraryFileReference());
    }

    /** 每个用例独立的 mock 图与待测服务。 */
    private static final class Mocks {
        private final AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        private final FileStorageService storageService = mock(FileStorageService.class);
        private final MinioStorage minioStorage = mock(MinioStorage.class);
        private final AssetClient assetClient = mock(AssetClient.class);
        private final LibraryReferenceAssetServiceImpl service;
        private final AtomicInteger inserts = new AtomicInteger();

        private Mocks() {
            // @RequiredArgsConstructor 生成 (assetMapper, storageService, minioStorage, assetClient) 顺序的构造器。
            this.service = new LibraryReferenceAssetServiceImpl(
                    assetMapper, storageService, minioStorage, assetClient);
        }

        /** 本人资产（scope=PROJECT 且已 link 到当前项目），media 列表为入参。 */
        private void stubOwnedAsset(List<MediaRef> media) {
            when(assetClient.getAssetInternal(eq(ASSET_ID), any())).thenReturn(new AssetDetail(
                    asset(AssetScope.PROJECT, PROJECT_ID, null), media, List.of()));
        }

        /** 库里还没有转存资产：任何判空查询都返回 null。 */
        private void stubEmptyAssetTable() {
            when(assetMapper.selectOne(any())).thenReturn(null);
            when(assetMapper.insert(any(AgentAsset.class))).thenAnswer(invocation -> {
                inserts.incrementAndGet();
                return 1;
            });
        }

        /** 资产行在 insert 成功之后才「可见」，用于真实地模拟第二次复用。 */
        private void stubAssetRowAppearsAfterInsert(AgentAsset row) {
            when(assetMapper.insert(any(AgentAsset.class))).thenAnswer(invocation -> {
                inserts.incrementAndGet();
                return 1;
            });
            when(assetMapper.selectOne(any()))
                    .thenAnswer(invocation -> inserts.get() == 0 ? null : row);
        }

        /** 字节读取与落盘都成功。 */
        private void stubTransfer() {
            when(minioStorage.get(OBJECT_KEY)).thenReturn(CONTENT);
            when(storageService.saveGeneratedFile(CONTENT, "设定.docx", DOCX_MIME))
                    .thenReturn(SAVED_URL);
        }
    }

    private static AgentReferenceRequest libraryFileReference() {
        return new AgentReferenceRequest(
                "library_file", MEDIA_ID, "@设定.docx", "设定.docx",
                ASSET_ID, MEDIA_ID, "/api/library/media/" + MEDIA_ID + "/content",
                DOCX_MIME, (long) CONTENT.length, null);
    }

    private static AssetSummary asset(AssetScope scope, String ownerProjectId, String linkedProjectId) {
        return new AssetSummary(
                ASSET_ID, USER_ID, scope, ownerProjectId,
                AssetType.DOCUMENT, null, "设定文档", null, null, false,
                null, AssetStatus.ACTIVE, null, 1L, List.of(),
                linkedProjectId == null
                        ? List.of()
                        : List.of(new ProjectLinkRef(linkedProjectId, OffsetDateTime.now())),
                null, List.of(), null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static MediaRef media(String mediaId, String fileName) {
        return new MediaRef(
                mediaId, "blob-" + mediaId, MediaType.DOCUMENT,
                "/api/files/" + OBJECT_KEY,
                "/api/library/media/" + mediaId + "/content",
                OBJECT_KEY, fileName, null, DOCX_MIME, (long) CONTENT.length,
                null, null, null, 0, MediaRole.REFERENCE, true, null);
    }
}
