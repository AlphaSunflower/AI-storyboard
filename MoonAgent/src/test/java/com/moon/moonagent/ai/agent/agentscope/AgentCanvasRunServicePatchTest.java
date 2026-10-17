package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布增量补丁：按 id upsert 累积进 message metadata、有界、turn 收尾清除。
 */
class AgentCanvasRunServicePatchTest {

    private static final String RUN_ID = "run-1";
    private static final String MESSAGE_ID = "message-1";

    // ── 纯函数：合并语义 ────────────────────────────────────────────────

    @Test
    void mergesSinglePatchIntoFixedShape() {
        AgentCanvasRunService.PatchAccumulation result =
                AgentCanvasRunService.accumulatePatch(null, Map.of(
                        "nodes", List.of(node("n1")),
                        "edges", List.of(edge("e1"))));

        assertThat(result.changed()).isTrue();
        assertThat(result.overflow()).isFalse();
        assertThat(result.patch()).containsOnlyKeys(
                "nodes", "edges", "removedNodeIds", "removedEdgeIds");
        assertThat(ids(result.patch(), "nodes")).containsExactly("n1");
        assertThat(ids(result.patch(), "edges")).containsExactly("e1");
    }

    @Test
    void laterUpsertOfSameIdWins() {
        Map<String, Object> existing = Map.of("nodes", List.of(node("n1", "旧名")));

        AgentCanvasRunService.PatchAccumulation result =
                AgentCanvasRunService.accumulatePatch(existing,
                        Map.of("nodes", List.of(node("n1", "新名"))));

        List<Map<String, Object>> nodes = maps(result.patch(), "nodes");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0)).containsEntry("label", "新名");
    }

    @Test
    void accumulatesAcrossCommandsWithoutLosingEarlierNodes() {
        // 同一批次里多条命令：metadata 是整份覆写，只留最后一条补丁会丢掉第一个命令建的节点
        AgentCanvasRunService.PatchAccumulation first =
                AgentCanvasRunService.accumulatePatch(null, Map.of("nodes", List.of(node("n1"))));
        AgentCanvasRunService.PatchAccumulation second =
                AgentCanvasRunService.accumulatePatch(first.patch(), Map.of(
                        "nodes", List.of(node("n2")),
                        "edges", List.of(edge("e1"))));

        assertThat(ids(second.patch(), "nodes")).containsExactly("n1", "n2");
        assertThat(ids(second.patch(), "edges")).containsExactly("e1");
    }

    @Test
    void removedIdsAreUnionedAndResolvedAgainstAdds() {
        AgentCanvasRunService.PatchAccumulation added =
                AgentCanvasRunService.accumulatePatch(null, Map.of(
                        "nodes", List.of(node("n1"), node("n2")),
                        "removedEdgeIds", List.of("e9")));
        AgentCanvasRunService.PatchAccumulation removed =
                AgentCanvasRunService.accumulatePatch(added.patch(), Map.of(
                        "removedNodeIds", List.of("n1"),
                        "removedEdgeIds", List.of("e8")));

        assertThat(ids(removed.patch(), "nodes")).containsExactly("n2");
        assertThat(strings(removed.patch(), "removedNodeIds")).containsExactly("n1");
        assertThat(strings(removed.patch(), "removedEdgeIds")).containsExactlyInAnyOrder("e8", "e9");

        // 同一个 id 先被删、后又被 upsert：删除标记必须让位（否则前端会把它删掉）
        AgentCanvasRunService.PatchAccumulation reAdded =
                AgentCanvasRunService.accumulatePatch(removed.patch(), Map.of(
                        "nodes", List.of(node("n1"))));
        assertThat(ids(reAdded.patch(), "nodes")).containsExactly("n2", "n1");
        assertThat(strings(reAdded.patch(), "removedNodeIds")).isEmpty();
    }

    @Test
    void invalidOrMissingPatchIsNotChanged() {
        assertThat(AgentCanvasRunService.accumulatePatch(null, null).changed()).isFalse();
        assertThat(AgentCanvasRunService.accumulatePatch(null, Map.of()).changed()).isFalse();
        assertThat(AgentCanvasRunService.accumulatePatch(null,
                Map.of("unexpected", "value")).changed()).isFalse();
        // 条目缺 id 一律跳过，不制造脏补丁
        assertThat(ids(AgentCanvasRunService.accumulatePatch(null,
                Map.of("nodes", List.of(Map.of("label", "no-id")))).patch(), "nodes")).isEmpty();
    }

    @Test
    void overflowWhenItemCountExceedsLimit() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int index = 0; index < 401; index++) {
            nodes.add(node("n" + index));
        }

        AgentCanvasRunService.PatchAccumulation result =
                AgentCanvasRunService.accumulatePatch(null, Map.of("nodes", nodes));

        assertThat(result.overflow()).isTrue();
    }

    @Test
    void overflowWhenSerializedPatchExceedsByteLimit() {
        // 条目数没超（<=400），但每个节点自带大字段：JSON 长度先到上限
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            nodes.add(node("n" + index, "x".repeat(1000)));
        }

        AgentCanvasRunService.PatchAccumulation result =
                AgentCanvasRunService.accumulatePatch(null, Map.of("nodes", nodes));

        assertThat(result.overflow()).isTrue();
    }

    // ── metadata 读写 ──────────────────────────────────────────────────

    @Test
    void writesPatchIntoMessageMetadataAndPreservesOtherKeys() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run", "status", "running", "progress", 30)));

        fixture.service.updateFromGraphResult(RUN_ID, Map.of(
                "ok", true,
                "revision", 5,
                "nodeCount", 2,
                "edgeCount", 1,
                "patch", Map.of("nodes", List.of(node("n1")), "edges", List.of(edge("e1")))));

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata)
                .containsEntry("kind", "canvas-run")
                .containsEntry("status", "running")
                .containsEntry("revision", 5)
                .containsEntry("nodeCount", 2)
                .containsEntry("edgeCount", 1);
        assertThat(ids(asMap(metadata.get("canvasPatch")), "nodes")).containsExactly("n1");
        assertThat(metadata).doesNotContainKey("canvasPatchOverflow");
    }

    @Test
    void invalidPatchLeavesMetadataUntouchedAndDoesNotThrow() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of("kind", "canvas-run", "progress", 30)));

        fixture.service.updateFromGraphResult(RUN_ID, Map.of(
                "ok", true, "revision", 2, "patch", "not-a-map"));

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata).doesNotContainKey("canvasPatch");
        assertThat(metadata).doesNotContainKey("canvasPatchOverflow");
        assertThat(metadata).containsEntry("revision", 2);
    }

    @Test
    void stopsAccumulatingAfterOverflow() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run", "canvasPatchOverflow", true,
                "canvasPatch", Map.of("nodes", List.of(node("n1"))))));

        fixture.service.updateFromGraphResult(RUN_ID, Map.of(
                "ok", true, "patch", Map.of("nodes", List.of(node("n2")))));

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata).containsEntry("canvasPatchOverflow", true);
        assertThat(metadata).doesNotContainKey("canvasPatch");
    }

    @Test
    void overflowingPatchClearsPatchAndLatchesOverflowFlag() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasPatch", Map.of("nodes", List.of(node("n1"))))));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int index = 0; index < 401; index++) {
            nodes.add(node("overflow-" + index));
        }

        fixture.service.updateFromGraphResult(RUN_ID, Map.of(
                "ok", true, "patch", Map.of("nodes", nodes)));

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata).containsEntry("canvasPatchOverflow", true);
        assertThat(metadata).doesNotContainKey("canvasPatch");
    }

    @Test
    void completeDropsPatchKeysButKeepsEverythingElse() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasPatch", Map.of("nodes", List.of(node("n1"))),
                "canvasPatchOverflow", false,
                "nodeCount", 2)));

        fixture.service.complete(RUN_ID, Map.of("nodeCount", 9, "revision", 12));

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata).doesNotContainKeys("canvasPatch", "canvasPatchOverflow");
        assertThat(metadata)
                .containsEntry("kind", "canvas-run")
                .containsEntry("status", "completed")
                .containsEntry("progress", 100)
                .containsEntry("nodeCount", 9)
                .containsEntry("revision", 12);
    }

    @Test
    void failDropsPatchKeysButKeepsError() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasPatch", Map.of("nodes", List.of(node("n1"))),
                "progress", 40)));

        fixture.service.fail(RUN_ID, "画布图命令失败");

        Map<String, Object> metadata = fixture.savedMetadata();
        assertThat(metadata).doesNotContainKeys("canvasPatch", "canvasPatchOverflow");
        assertThat(metadata)
                .containsEntry("status", "failed")
                .containsEntry("error", "画布图命令失败");
    }

    // ── 直播 payload 片段：补丁只在 metadata，必须显式取出 ──────────────

    @Test
    void canvasPatchPayloadExposesAccumulatedPatchForLiveSse() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasPatch", Map.of(
                        "nodes", List.of(node("n1")),
                        "edges", List.of(edge("e1"))))));

        Map<String, Object> payload = fixture.service.canvasPatchPayload(RUN_ID);

        assertThat(payload).containsKey("canvasPatch");
        Map<String, Object> patch = asMap(payload.get("canvasPatch"));
        assertThat(ids(patch, "nodes")).containsExactly("n1");
        assertThat(ids(patch, "edges")).containsExactly("e1");
        // 溢出标记未置位时不要带上它，避免前端误判成"补丁不可信"
        assertThat(payload).doesNotContainKey("canvasPatchOverflow");
    }

    @Test
    void canvasPatchPayloadCarriesOverflowFlagWithoutPatch() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasPatchOverflow", true)));

        assertThat(fixture.service.canvasPatchPayload(RUN_ID))
                .containsEntry("canvasPatchOverflow", true)
                .doesNotContainKey("canvasPatch");
    }

    @Test
    void canvasPatchPayloadIsEmptyWithoutPatchOrRun() {
        Fixture fixture = new Fixture();
        fixture.givenMessage(new LinkedHashMap<>(Map.of("kind", "canvas-run")));

        assertThat(fixture.service.canvasPatchPayload(RUN_ID)).isEmpty();
        assertThat(fixture.service.canvasPatchPayload(null)).isEmpty();
        assertThat(fixture.service.canvasPatchPayload("missing-run")).isEmpty();
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────

    private static final class Fixture {

        private final AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        private final AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper,
                mock(AgentCanvasRunOperationMapper.class),
                mock(MorrowCanvasService.class),
                messageMapper);

        private AgentMessage message;

        private void givenMessage(Map<String, Object> metadata) {
            AgentCanvasRunEntity entity = new AgentCanvasRunEntity();
            entity.setId(RUN_ID);
            entity.setConversationId("conversation-1");
            entity.setCanvasId("canvas-1");
            entity.setMessageId(MESSAGE_ID);
            entity.setStatus("running");
            entity.setProgress(30);
            entity.setNodeCount(2);
            entity.setEdgeCount(1);
            entity.setRevision(4);
            when(runMapper.selectById(RUN_ID)).thenReturn(entity);

            message = new AgentMessage();
            message.setId(MESSAGE_ID);
            message.setConversationId("conversation-1");
            message.setMetadata(metadata);
            when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        }

        private Map<String, Object> savedMetadata() {
            ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
            verify(messageMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
            return captor.getValue().getMetadata();
        }
    }

    private static Map<String, Object> node(String id) {
        return node(id, id);
    }

    private static Map<String, Object> node(String id, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "image");
        node.put("label", label);
        return node;
    }

    private static Map<String, Object> edge(String id) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", "n1");
        edge.put("target", "n2");
        return edge;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> patch, String key) {
        Object value = patch.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<String> ids(Map<String, Object> patch, String key) {
        return maps(patch, key).stream().map(item -> String.valueOf(item.get("id"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> patch, String key) {
        Object value = patch.get(key);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
