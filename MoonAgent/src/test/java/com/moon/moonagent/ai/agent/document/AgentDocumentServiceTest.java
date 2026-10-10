package com.moon.moonagent.ai.agent.document;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentDocumentIndex;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentDocumentIndexMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDocumentServiceTest {

    @Test
    void readsOwnedDocumentFromMinioAndPaginatesMarkdown() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        AgentDocumentIndexMapper indexMapper = mock(AgentDocumentIndexMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        AgentDocumentService service = service(assetMapper, indexMapper, storage);
        AgentAsset asset = asset("/api/files/files/doc.md", "c1");
        when(assetMapper.selectOne(any())).thenReturn(asset);
        when(storage.readFile("doc.md"))
                .thenReturn("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
        when(indexMapper.selectOne(any())).thenReturn(index("ready", "已有摘要"));

        DocumentReadResult result = service.read("c1", "a1", 10, 5);

        assertThat(result.markdown()).isEqualTo("klmno");
        assertThat(result.offset()).isEqualTo(10);
        assertThat(result.nextOffset()).isEqualTo(15);
        assertThat(result.totalChars()).isEqualTo(26);
        assertThat(result.summary()).isEqualTo("已有摘要");
        assertThat(result.name()).isEqualTo("剧本.md");
    }

    @Test
    void rejectsAssetOwnedByAnotherConversation() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        AgentDocumentService service = service(
                assetMapper, mock(AgentDocumentIndexMapper.class), mock(FileStorageService.class));
        when(assetMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.read("c1", "outside", 0, 100))
                .isInstanceOf(DocumentParseException.class)
                .extracting("code")
                .isEqualTo("not_found");
    }

    @Test
    void firstReadPersistsSummaryAfterParsing() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        AgentDocumentIndexMapper indexMapper = mock(AgentDocumentIndexMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        DocumentProperties properties = new DocumentProperties();
        properties.setSummaryChunkChars(30);
        DocumentSummaryService summary = new DocumentSummaryService(properties, new FakeSummaryModel());
        AgentDocumentService service = new AgentDocumentService(
                assetMapper, indexMapper, storage, new DocumentParsingService(properties),
                summary, properties);
        when(assetMapper.selectOne(any())).thenReturn(asset("/api/files/files/doc.md", "c1"));
        when(storage.readFile("doc.md"))
                .thenReturn("# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8));
        when(indexMapper.selectOne(any())).thenReturn(null);

        service.read("c1", "a1", 0, 100);

        verify(indexMapper).insert(any(AgentDocumentIndex.class));
        verify(indexMapper, timeout(2_000)).updateById(any(AgentDocumentIndex.class));
    }

    @Test
    void listsUploadedDocumentsBeforeTheyHaveBeenReadOrSummarized() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        AgentDocumentIndexMapper indexMapper = mock(AgentDocumentIndexMapper.class);
        AgentDocumentService service = service(
                assetMapper, indexMapper, mock(FileStorageService.class));
        AgentAsset asset = asset("/api/files/files/plan.docx", "c1");
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));
        when(indexMapper.selectList(any())).thenReturn(List.of());

        List<DocumentSummaryView> rows = service.recentSummaries("c1", 5);

        assertThat(rows).singleElement()
                .satisfies(row -> {
                    assertThat(row.assetId()).isEqualTo("a1");
                    assertThat(row.name()).isEqualTo("剧本.md");
                    assertThat(row.summary()).isEmpty();
                    assertThat(row.status()).isEqualTo("unread");
                });
    }

    private static AgentDocumentService service(AgentAssetMapper assetMapper,
                                                AgentDocumentIndexMapper indexMapper,
                                                FileStorageService storage) {
        DocumentProperties properties = new DocumentProperties();
        return new AgentDocumentService(
                assetMapper, indexMapper, storage,
                new DocumentParsingService(properties),
                new DocumentSummaryService(properties, new FakeSummaryModel()),
                properties);
    }

    private static AgentAsset asset(String url, String conversationId) {
        AgentAsset asset = new AgentAsset();
        asset.setId("a1");
        asset.setConversationId(conversationId);
        asset.setType("reference");
        asset.setKind("file");
        asset.setUrl(url);
        asset.setOriginalName("剧本.md");
        asset.setMimeType("text/markdown");
        return asset;
    }

    private static AgentDocumentIndex index(String status, String summary) {
        AgentDocumentIndex index = new AgentDocumentIndex();
        index.setAssetId("a1");
        index.setConversationId("c1");
        index.setOriginalName("剧本.md");
        index.setMimeType("text/markdown");
        index.setStatus(status);
        index.setSummary(summary);
        index.setCharCount(26);
        return index;
    }

    private static final class FakeSummaryModel implements DocumentSummaryModel {
        @Override
        public String summarizeChunk(String markdown) {
            return "摘要:" + markdown;
        }

        @Override
        public String merge(List<String> summaries) {
            return String.join("|", summaries);
        }
    }
}
