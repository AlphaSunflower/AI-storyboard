package com.moon.moonagent.ai.agent.sandbox;

import com.moon.moonagent.entity.AgentConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SandboxOutputReconciler {

    private static final Logger log = LoggerFactory.getLogger(SandboxOutputReconciler.class);

    private final MoonSandboxClient client;
    private final SandboxArtifactPublisher publisher;

    public SandboxOutputReconciler(MoonSandboxClient client,
                                   SandboxArtifactPublisher publisher) {
        this.client = client;
        this.publisher = publisher;
    }

    /**
     * 全量扫描 {@code output/}，返回命中的全部资产（含此前已发布的）。
     *
     * <p>注意：重复内容会命中既有 {@code agent_assets} 行，因此本方法会把历史产物也一并返回。
     * 需要「只挂本次新产物」的场景请用 {@link #reconcileNew}。
     */
    public SandboxArtifactPublisher.PublishedArtifacts reconcile(
            AgentConversation conversation, String taskId) {
        return scan(conversation, taskId, false);
    }

    /**
     * 扫描 {@code output/} 并只返回<b>本次新建</b>的资产。
     *
     * <p>用于两类补扫：① 异步任务（如 {@code run_python_script}）执行完成后的产物发布，
     * 避免"任务写文件晚于回合末扫描"导致产物只能在更晚的回合出现；
     * ② 回合末扫描，避免每个后续回合把历史产物重新挂成自己的附件（红线 5：不得制造重复卡片）。
     */
    public SandboxArtifactPublisher.PublishedArtifacts reconcileNew(
            AgentConversation conversation, String taskId) {
        return scan(conversation, taskId, true);
    }

    private SandboxArtifactPublisher.PublishedArtifacts scan(
            AgentConversation conversation, String taskId, boolean onlyNew) {
        if (conversation == null || !client.enabled()) {
            return SandboxArtifactPublisher.PublishedArtifacts.EMPTY;
        }
        List<String> assetIds = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        try {
            for (SandboxFileEntry file : client.list(conversation.getId(), "output")) {
                if (file.directory()) continue;
                SandboxArtifactPublisher.PublishedArtifacts published = onlyNew
                        ? publisher.publishPathIfNew(conversation, taskId,
                        file.path(), file.sizeBytes(), file.mimeType())
                        : publisher.publishPath(conversation, taskId,
                        file.path(), file.sizeBytes(), file.mimeType());
                assetIds.addAll(published.assetIds());
                urls.addAll(published.urls());
            }
        } catch (RuntimeException error) {
            log.warn("Sandbox output reconciliation failed conversationId={}: {}",
                    conversation.getId(), error.getMessage());
        }
        return new SandboxArtifactPublisher.PublishedArtifacts(
                List.copyOf(assetIds), List.copyOf(urls));
    }
}
