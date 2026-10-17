package com.moon.moonagent.service;

import com.moon.moonagent.ai.agent.CanvasMediaRef;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentGenerationPreferencesRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnMediaCanonicalizerTest {

    private final TurnMediaCanonicalizer canonicalizer = new TurnMediaCanonicalizer(
            "http://backend:8082", "http://asset-service:8085", "http://minio:9000", "");

    @Test
    void browserOriginMediaUrlsAreCanonicalizedForEveryField() {
        AgentSendMessageRequest request = new AgentSendMessageRequest(
                "按这张图搭链路", "req-1",
                "http://localhost:5173/api/files/images/legacy.png",
                List.of("http://localhost:5173/api/files/images/a.png?token=1.abc"),
                List.of("http://localhost:5173/api/files/videos/b.mp4"),
                List.of("http://localhost:5173/api/files/audios/c.mp3"),
                List.of(new AgentAttachmentRequest(
                        "http://localhost/api/files/images/d.png?token=2.def",
                        "d.png", "image/png", 12L, "image", "upload", "asset-1", null)),
                List.of(new AgentReferenceRequest(
                        "local_file", "asset-doc", "@设定.docx", "设定.docx", "asset-doc", null,
                        "http://localhost:5173/api/files/files/plan.docx?token=9.xyz",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        123L, null)),
                List.of("asset-1"),
                List.of(), List.of(),
                "intent-canvas", Boolean.TRUE, "message-1",
                new CanvasSessionContext(
                        CanvasSessionContext.EDIT_MODE, "project-1", "canvas-1",
                        List.of(new CanvasMediaRef("image", null,
                                "http://localhost:5173/api/files/images/e.png?token=3.ghi",
                                "e.png", "image/png", "upload")),
                        List.of("http://localhost:5173/api/files/images/f.png")),
                new AgentGenerationPreferencesRequest(Boolean.TRUE, Boolean.FALSE, null, null));

        AgentSendMessageRequest canonical = canonicalizer.canonicalize(request);

        assertThat(canonical.picUrl()).isEqualTo("/api/files/images/legacy.png");
        assertThat(canonical.picUrls()).containsExactly("/api/files/images/a.png?token=1.abc");
        assertThat(canonical.referenceVideos()).containsExactly("/api/files/videos/b.mp4");
        assertThat(canonical.referenceAudios()).containsExactly("/api/files/audios/c.mp3");
        assertThat(canonical.attachments()).hasSize(1);
        assertThat(canonical.attachments().getFirst().url()).isEqualTo("/api/files/images/d.png?token=2.def");
        assertThat(canonical.attachments().getFirst().name()).isEqualTo("d.png");
        assertThat(canonical.attachments().getFirst().mimeType()).isEqualTo("image/png");
        assertThat(canonical.attachments().getFirst().sizeBytes()).isEqualTo(12L);
        assertThat(canonical.attachments().getFirst().assetId()).isEqualTo("asset-1");
        assertThat(canonical.references()).hasSize(1);
        assertThat(canonical.references().getFirst().url())
                .isEqualTo("/api/files/files/plan.docx?token=9.xyz");
        assertThat(canonical.references().getFirst().token()).isEqualTo("@设定.docx");
        assertThat(canonical.canvasContext().normalizedMediaRefs().getFirst().url())
                .isEqualTo("/api/files/images/e.png?token=3.ghi");
        assertThat(canonical.canvasContext().normalizedExcludedMediaUrls())
                .containsExactly("/api/files/images/f.png");
    }

    @Test
    void nonMediaFieldsAndNullPayloadsArePreserved() {
        AgentSendMessageRequest request = new AgentSendMessageRequest(
                "hello", "req-2", null, null, null, null, null, null,
                List.of("asset-9"), List.of("group-1"), List.of("snapshot-1"),
                "intent-storyboard", null, null, null, null);

        AgentSendMessageRequest canonical = canonicalizer.canonicalize(request);

        assertThat(canonical.content()).isEqualTo("hello");
        assertThat(canonical.clientRequestId()).isEqualTo("req-2");
        assertThat(canonical.picUrls()).isNull();
        assertThat(canonical.attachments()).isNull();
        assertThat(canonical.references()).isNull();
        assertThat(canonical.canvasContext()).isNull();
        assertThat(canonical.assetIds()).containsExactly("asset-9");
        assertThat(canonical.assetGroupIds()).containsExactly("group-1");
        assertThat(canonical.assetGroupSnapshotIds()).containsExactly("snapshot-1");
        assertThat(canonical.routingHint()).isEqualTo("intent-storyboard");
        assertThat(canonicalizer.canonicalize(null)).isNull();
        assertThat(canonicalizer.canonicalizeCanvasContext(null)).isNull();
    }

    @Test
    void untrustedMediaUrlsAreRejected() {
        AgentSendMessageRequest request = new AgentSendMessageRequest(
                "hello", "req-3", null,
                List.of("http://localhost:5173/other/a.png"),
                null, null, null, null, List.of(), List.of(), List.of(),
                null, null, null, null, null);

        assertThatThrownBy(() -> canonicalizer.canonicalize(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("媒体地址不在允许的服务范围内");
    }

    /**
     * 引用只是附加资料：local_file 的非法地址只置空该条 url（不抛 40001），
     * 其它类别（项目库 / 分镜 / 会话）的 url 原样保留。
     */
    @Test
    void referenceUrlsAreCanonicalizedWithoutRejectingTheTurn() {
        AgentSendMessageRequest request = new AgentSendMessageRequest(
                "看看这几个引用", "req-4", null, null, null, null, null,
                List.of(
                        new AgentReferenceRequest("local_file", "asset-a", "@脚本.docx", "脚本.docx",
                                "asset-a", null, "http://localhost:5173/api/files/files/a.docx",
                                "application/msword", 1L, null),
                        new AgentReferenceRequest("local_file", "asset-b", "@坏地址.docx", "坏地址.docx",
                                "asset-b", null, "http://evil.example.com/other/b.docx",
                                "application/msword", 1L, null),
                        new AgentReferenceRequest("library_file", "media-1", "@设定.docx", "设定.docx",
                                null, "media-1", "http://localhost:5173/api/library/media/media-1/content",
                                "application/msword", 2L, null),
                        new AgentReferenceRequest("scene", "scene-1", "@分镜3", "分镜3",
                                null, null, null, null, null, 3),
                        new AgentReferenceRequest("conversation", "conv-2", "@对话《分镜重做》",
                                "分镜重做", null, null, null, null, null, null)),
                List.of(), List.of(), List.of(), null, null, null, null, null);

        AgentSendMessageRequest canonical = canonicalizer.canonicalize(request);

        assertThat(canonical.references()).hasSize(5);
        assertThat(canonical.references().get(0).url()).isEqualTo("/api/files/files/a.docx");
        assertThat(canonical.references().get(1).url()).isNull();
        assertThat(canonical.references().get(1).assetId()).isEqualTo("asset-b");
        assertThat(canonical.references().get(2).url())
                .isEqualTo("http://localhost:5173/api/library/media/media-1/content");
        assertThat(canonical.references().get(3).url()).isNull();
        assertThat(canonical.references().get(3).sceneNumber()).isEqualTo(3);
        assertThat(canonical.references().get(4).token()).isEqualTo("@对话《分镜重做》");
    }

    @Test
    void configuredExtraOriginsAreAcceptedUnchanged() {
        TurnMediaCanonicalizer withCdn = new TurnMediaCanonicalizer(
                "http://backend:8082", "http://asset-service:8085", "http://minio:9000",
                "https://cdn.example.com, https://oss.example.com");

        assertThat(withCdn.canonicalizeCanvasContext(new CanvasSessionContext(
                CanvasSessionContext.EDIT_MODE, "project-1", "canvas-1",
                List.of(new CanvasMediaRef("image", null,
                        "https://cdn.example.com/media/a.png", "a.png", "image/png", "upload")),
                List.of())).normalizedMediaRefs().getFirst().url())
                .isEqualTo("https://cdn.example.com/media/a.png");
    }
}
