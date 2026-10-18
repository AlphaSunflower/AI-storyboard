package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 单条超大工具结果的溢出兜底。
 *
 * <p>默认**不截断**任何工具结果；只有当前请求估算会超过模型窗口的 90% 时，才把最大的
 * 工具结果完整写入沙箱 {@code work/overflow/<toolCallId>.txt}，上下文里替换成一条
 * 「已落盘 + read_file 路径」的定位符。内容没有丢：模型（以及用户）随时可以读回全文。
 *
 * <p>这是最后一道保险，不是常规驱逐：正常大小的结果不会被改写；没有沙箱（无法读回）时
 * 直接放行，绝不退化成“截断后丢掉”。
 */
final class ContextOverflowMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ContextOverflowMiddleware.class);

    /** 单条结果小于这个字符数时视为正常，不做溢出处理。 */
    private static final int MIN_SPILL_CHARS = 20_000;
    /** 最多连续处理几条最大的结果，避免一次请求把所有工具结果都挪走。 */
    private static final int MAX_SPILLS = 3;
    /** 估算窗口的占用上限；留出输出与系统提示的余量。 */
    private static final double BUDGET_RATIO = 0.9;

    private final MoonSandboxClient sandboxClient;

    ContextOverflowMiddleware(MoonSandboxClient sandboxClient) {
        this.sandboxClient = sandboxClient;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (sandboxClient == null || !(agent instanceof ReActAgent reAct)) {
            return next.apply(input);
        }
        AgentScopeRunContext run = ctx == null ? null : ctx.get(AgentScopeRunContext.class);
        String conversationId = run == null || run.conversation() == null
                ? null : run.conversation().getId();
        int window = reAct.getModel() == null ? 0 : reAct.getModel().getContextWindowSize();
        if (conversationId == null || window <= 0) {
            return next.apply(input);
        }
        int budget = Math.max(4096, (int) Math.floor(window * BUDGET_RATIO));
        List<Msg> messages = new ArrayList<>(input.messages());
        int before = TokenCounterUtil.calculateToken(messages);
        if (before <= budget) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        for (int attempt = 0; attempt < MAX_SPILLS; attempt++) {
            SpillTarget target = largestToolResult(messages);
            if (target == null) {
                break;
            }
            String path = "work/overflow/" + safeName(target.block().getId()) + ".txt";
            try {
                sandboxClient.write(conversationId, path, target.text(), "OVERWRITE");
            } catch (RuntimeException e) {
                log.warn("工具结果溢出落盘失败，保留原结果: conversationId={}, path={}, error={}",
                        conversationId, path, e.getMessage());
                break;
            }
            ToolResultBlock replacement = new ToolResultBlock(
                    target.block().getId(),
                    target.block().getName(),
                    List.of(TextBlock.builder()
                            .text(placeholder(path, target.text().length()))
                            .build()),
                    target.block().getMetadata(),
                    target.block().getState());
            Msg replaced = replaceBlock(messages.get(target.messageIndex()), target.blockIndex(),
                    replacement);
            messages.set(target.messageIndex(), replaced);
            replaceInState(state, target, replaced);
            int after = TokenCounterUtil.calculateToken(messages);
            log.info("工具结果溢出已落盘: conversationId={}, path={}, chars={}, tokens {} -> {}",
                    conversationId, path, target.text().length(), before, after);
            before = after;
            if (after <= budget) {
                break;
            }
        }
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    private static SpillTarget largestToolResult(List<Msg> messages) {
        SpillTarget largest = null;
        int largestChars = MIN_SPILL_CHARS;
        for (int i = 0; i < messages.size(); i++) {
            List<ContentBlock> content = messages.get(i).getContent();
            if (content == null) continue;
            for (int j = 0; j < content.size(); j++) {
                if (!(content.get(j) instanceof ToolResultBlock block)) continue;
                String text = textOf(block);
                if (text.length() > largestChars) {
                    largestChars = text.length();
                    largest = new SpillTarget(i, j, block, text);
                }
            }
        }
        return largest;
    }

    private static Msg replaceBlock(Msg message, int blockIndex, ToolResultBlock replacement) {
        List<ContentBlock> content = new ArrayList<>(message.getContent());
        content.set(blockIndex, replacement);
        return message.withContent(content);
    }

    private static void replaceInState(AgentState state, SpillTarget target, Msg replaced) {
        if (state == null || state.contextMutable() == null) return;
        List<Msg> context = state.contextMutable();
        for (int i = 0; i < context.size(); i++) {
            Msg message = context.get(i);
            if (message.getId() != null
                    && message.getId().equals(replaced.getId())) {
                context.set(i, replaced);
                return;
            }
        }
    }

    private static String textOf(ToolResultBlock block) {
        StringBuilder out = new StringBuilder();
        for (ContentBlock item : block.getOutput()) {
            if (item instanceof TextBlock text && text.getText() != null) {
                if (!out.isEmpty()) out.append('\n');
                out.append(text.getText());
            }
        }
        return out.toString();
    }

    private static String placeholder(String path, int chars) {
        return "[工具结果过大，已完整保存到 " + path + "（" + chars
                + " 字符）。请用 read_file 读取全文，不要重复调用原工具。]";
    }

    private static String safeName(String id) {
        if (id == null || id.isBlank()) return "tool-result";
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record SpillTarget(int messageIndex, int blockIndex,
                               ToolResultBlock block, String text) {
    }
}
