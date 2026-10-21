package com.moon.moonagent.ai.agent.archive;

import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.context.ConversationContextCleanupService;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxSessionRegistry;
import com.moon.moonagent.entity.AgentArchiveCleanup;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentArchiveCleanupMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 已归档会话的定期清理：把「归档后不会再被使用」的运行时数据释放掉，控制数据库与沙箱磁盘增长。
 *
 * <p><b>清理边界（重要）</b>：只删数据库里的运行时派生行；<b>MinIO 里的图片/文档对象一律不删</b>，
 * 消息、{@code agent_assets} 行、审计记录也都保留，归档会话仍然能打开看历史、下载文件。
 * 具体删/留的表见 {@link ConversationContextCleanupService#cleanupArchivedRuntime}。
 *
 * <p>每个会话只清一次，清完往 {@code agent_archive_cleanups} 写台账；台账表缺省时任务整体跳过并
 * 打印可执行的迁移提示（不会静默地反复清理同一批会话）。
 */
@Service
@RequiredArgsConstructor
public class ArchivedConversationCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ArchivedConversationCleanupService.class);

    private final AgentArchiveCleanupMapper archiveCleanupMapper;
    private final ConversationContextCleanupService contextCleanupService;
    private final ConversationLock conversationLock;
    private final ArchiveCleanupProperties properties;
    private final ObjectProvider<MoonAgentRuntime> runtimeProvider;
    private final ObjectProvider<MoonSandboxClient> sandboxClientProvider;
    private final ObjectProvider<SandboxSessionRegistry> sandboxSessionRegistryProvider;

    /**
     * 扫一轮：按批领取「归档已过宽限期且没有台账记录」的会话，逐个清理。
     *
     * @return 本轮成功清理的会话数
     */
    public int runOnce() {
        if (!properties.isEnabled()) return 0;
        int cleaned = 0;
        try {
            for (int round = 0; round < properties.getMaxBatches(); round++) {
                List<AgentConversation> batch = archiveCleanupMapper.findDueForCleanup(
                        properties.getGraceDays(), properties.getBatchSize());
                if (batch.isEmpty()) break;

                int cleanedThisRound = 0;
                for (AgentConversation conversation : batch) {
                    try {
                        if (cleanupOne(conversation)) cleanedThisRound++;
                    } catch (Exception e) {
                        // 单个会话失败不影响同批其它会话，下一轮会再试（台账未写）
                        log.warn("清理已归档会话失败: conversationId={}, error={}",
                                conversation.getId(), e.getMessage(), e);
                    }
                }
                cleaned += cleanedThisRound;
                // 一整批都没清掉（锁住 / 状态已变）说明再查还是同一批，结束本轮
                if (cleanedThisRound == 0 || batch.size() < properties.getBatchSize()) break;
            }
        } catch (Exception e) {
            log.error("已归档会话清理中止（若缺少 public.agent_archive_cleanups 表，"
                    + "请先执行 db/migration/V54__agent_archive_cleanups.sql）: {}", e.getMessage(), e);
        }
        if (cleaned > 0) {
            log.info("已归档会话清理完成：{} 个会话（图片/文档 MinIO 对象均已保留）", cleaned);
        }
        return cleaned;
    }

    /**
     * 清理单个已归档会话（幂等）。
     *
     * @return true=本轮确实做了清理并写入台账
     */
    public boolean cleanupOne(AgentConversation conversation) {
        if (conversation == null) return false;
        String conversationId = conversation.getId();
        if (conversationId == null || conversationId.isBlank()) return false;
        if (!"archived".equalsIgnoreCase(conversation.getStatus())) return false;
        if (archiveCleanupMapper.selectById(conversationId) != null) return false;
        if (conversationLock.isLocked(conversationId)) {
            log.info("已归档会话仍有在途任务，跳过本次清理: conversationId={}", conversationId);
            return false;
        }

        ConversationContextCleanupService.ArchiveCleanupReport report =
                contextCleanupService.cleanupArchivedRuntime(conversationId);

        // 运行时状态必须经 runtime 清（同时清本地 stateCache）；沙箱工作区是外部 HTTP 调用
        clearRuntimeState(conversation);
        clearSandboxWorkspace(conversationId);

        markCleaned(conversation, report);
        log.info("已归档会话清理: conversationId={}, deletedRows={}, {}", conversationId, report.total(), report);
        return true;
    }

    private void clearRuntimeState(AgentConversation conversation) {
        MoonAgentRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null || !runtime.enabled()) return;
        try {
            runtime.clearSession(conversation.getUserId(), conversation.getId());
        } catch (Exception e) {
            log.warn("清理已归档会话的 AgentScope 状态失败: conversationId={}, error={}",
                    conversation.getId(), e.getMessage());
        }
    }

    private void clearSandboxWorkspace(String conversationId) {
        MoonSandboxClient client = sandboxClientProvider.getIfAvailable();
        if (client != null && client.enabled()) {
            try {
                client.cleanup(conversationId);
            } catch (Exception e) {
                // 沙箱不可用不影响数据库清理；工作区由 MoonSandbox 的过期清理兜底
                log.warn("清理已归档会话的沙箱工作区失败: conversationId={}, error={}",
                        conversationId, e.getMessage());
            }
        }
        SandboxSessionRegistry registry = sandboxSessionRegistryProvider.getIfAvailable();
        if (registry != null) registry.markCleaned(conversationId);
    }

    private void markCleaned(AgentConversation conversation,
                             ConversationContextCleanupService.ArchiveCleanupReport report) {
        AgentArchiveCleanup marker = new AgentArchiveCleanup();
        marker.setConversationId(conversation.getId());
        marker.setUserId(conversation.getUserId());
        marker.setDeletedRows(report.total());
        marker.setDetail(report.toString());
        marker.setCleanedAt(OffsetDateTime.now());
        try {
            archiveCleanupMapper.insert(marker);
        } catch (DuplicateKeyException e) {
            // 多实例同时清理同一会话：删除本身幂等，台账写不进去也无害
            log.debug("归档清理台账已存在: conversationId={}", conversation.getId());
        }
    }
}
