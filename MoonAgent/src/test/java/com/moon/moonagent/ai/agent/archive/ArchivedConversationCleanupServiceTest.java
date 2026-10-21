package com.moon.moonagent.ai.agent.archive;

import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.context.ConversationContextCleanupService;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxSessionRegistry;
import com.moon.moonagent.entity.AgentArchiveCleanup;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentArchiveCleanupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 已归档会话清理：只清运行时数据、写一次台账、跳过锁住/已恢复/已清过的会话。
 *
 * <p>「不删 MinIO 图片/文档」由结构保证：{@link ConversationContextCleanupService#cleanupArchivedRuntime}
 * 是唯一的删除入口，它没有对象存储依赖，也不碰 {@code agent_assets} / {@code agent_messages}。
 */
@ExtendWith(MockitoExtension.class)
class ArchivedConversationCleanupServiceTest {

    @Mock private AgentArchiveCleanupMapper archiveCleanupMapper;
    @Mock private ConversationContextCleanupService contextCleanupService;
    @Mock private ConversationLock conversationLock;
    @Mock private ObjectProvider<MoonAgentRuntime> runtimeProvider;
    @Mock private ObjectProvider<MoonSandboxClient> sandboxClientProvider;
    @Mock private ObjectProvider<SandboxSessionRegistry> sandboxSessionRegistryProvider;

    private ArchivedConversationCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ArchivedConversationCleanupService(archiveCleanupMapper, contextCleanupService,
                conversationLock, new ArchiveCleanupProperties(),
                runtimeProvider, sandboxClientProvider, sandboxSessionRegistryProvider);
    }

    @Test
    void cleansArchivedConversationAndWritesLedger() {
        AgentConversation conversation = conversation("c-1", "archived");
        when(archiveCleanupMapper.selectById("c-1")).thenReturn(null);
        when(conversationLock.isLocked("c-1")).thenReturn(false);
        when(contextCleanupService.cleanupArchivedRuntime("c-1")).thenReturn(report(10));

        assertThat(service.cleanupOne(conversation)).isTrue();

        verify(contextCleanupService).cleanupArchivedRuntime("c-1");
        ArgumentCaptor<AgentArchiveCleanup> captor = ArgumentCaptor.forClass(AgentArchiveCleanup.class);
        verify(archiveCleanupMapper).insert(captor.capture());        assertThat(captor.getValue().getConversationId()).isEqualTo("c-1");
        assertThat(captor.getValue().getDeletedRows()).isEqualTo(10);
    }

    @Test
    void skipsConversationThatIsLocked() {
        AgentConversation conversation = conversation("c-1", "archived");
        when(archiveCleanupMapper.selectById("c-1")).thenReturn(null);
        when(conversationLock.isLocked("c-1")).thenReturn(true);

        assertThat(service.cleanupOne(conversation)).isFalse();

        verify(contextCleanupService, never()).cleanupArchivedRuntime(any());
        verify(archiveCleanupMapper, never()).insert(any(AgentArchiveCleanup.class));
    }

    @Test
    void skipsConversationThatWasAlreadyCleaned() {
        AgentConversation conversation = conversation("c-1", "archived");
        when(archiveCleanupMapper.selectById("c-1")).thenReturn(new AgentArchiveCleanup());

        assertThat(service.cleanupOne(conversation)).isFalse();

        verify(contextCleanupService, never()).cleanupArchivedRuntime(any());
    }

    @Test
    void skipsConversationThatWasRestored() {
        AgentConversation conversation = conversation("c-1", "active");

        assertThat(service.cleanupOne(conversation)).isFalse();

        verify(contextCleanupService, never()).cleanupArchivedRuntime(any());
    }

    @Test
    void runOnceProcessesDueBatchAndStops() {
        AgentConversation conversation = conversation("c-1", "archived");
        when(archiveCleanupMapper.findDueForCleanup(anyInt(), anyInt())).thenReturn(List.of(conversation));
        when(archiveCleanupMapper.selectById("c-1")).thenReturn(null);
        when(conversationLock.isLocked("c-1")).thenReturn(false);
        when(contextCleanupService.cleanupArchivedRuntime("c-1")).thenReturn(report(4));

        assertThat(service.runOnce()).isEqualTo(1);

        // 未满一批即结束，不再发起第二次查询
        verify(archiveCleanupMapper).findDueForCleanup(anyInt(), anyInt());
    }

    @Test
    void runOnceSwallowsMissingLedgerTable() {
        when(archiveCleanupMapper.findDueForCleanup(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("relation \"agent_archive_cleanups\" does not exist"));

        // 缺迁移时只记录错误并跳过，不影响其它定时任务
        assertThat(service.runOnce()).isZero();
    }

    private static AgentConversation conversation(String id, String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setUserId("user-1");
        conversation.setStatus(status);
        return conversation;
    }

    private static ConversationContextCleanupService.ArchiveCleanupReport report(int rows) {
        return new ConversationContextCleanupService.ArchiveCleanupReport(
                rows, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
