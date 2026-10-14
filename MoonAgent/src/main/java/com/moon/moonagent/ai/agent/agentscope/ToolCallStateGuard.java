package com.moon.moonagent.ai.agent.agentscope;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用生命周期状态守卫：一次调用只能从 {@code RUNNING} 前进到某一个终态，终态不可再改写。
 *
 * <p>键为 {@code (turnId, toolCallId)}，与工具审计使用的关联 ID 完全一致（不新造 correlation id）。
 * 生命周期词表：
 * <ul>
 *   <li>{@code RUNNING}：已开始、未结束（非终态）。</li>
 *   <li>{@code SUCCESS}：{@code ToolResultContract} 的 {@code ok|queued|partial}。</li>
 *   <li>{@code ERROR}：{@code error}。</li>
 *   <li>{@code DENIED}：{@code rejected}。</li>
 *   <li>{@code NEEDS_CONFIRMATION}：{@code needs_confirmation}（暂停态，同样是终态）。</li>
 *   <li>{@code INTERRUPTED}：调用被取消/中断（异常路径）。</li>
 * </ul>
 *
 * <p>违反不变式（同一调用二次 {@code complete}、终态之后再 {@code complete}、未 {@code begin} 就
 * {@code complete}、状态词表外的 {@code resultStatus}）一律抛 {@link IllegalStateException}，
 * 消息里带 {@code turnId/toolCallId/from/to}；按 CLAUDE.md 要求显式失败，绝不静默忽略或降级。
 *
 * <p>身份缺失（{@code toolCallId} 为空，例如非 AgentScope 工具路径没有 {@code ToolUseBlock}）时
 * 守卫整体跳过：合成键会让互不相关的调用互相误判，静默跳过比误报终止安全，且此路径没有可守卫的调用身份。
 *
 * <p>内存边界：外层按 turn 分桶，桶内最多保留 {@value #MAX_CALLS_PER_TURN} 条<b>终态</b>记录，
 * 超出后按插入顺序从最旧的终态记录开始淘汰。桶的实际大小上界是
 * {@code MAX_CALLS_PER_TURN + 仍在执行的调用条数}：{@code RUNNING} 记录永不淘汰
 * （淘汰执行中的调用会让 {@code complete} 误判为“未 begin”，代价高于内存收益），
 * 因此单轮内真正并发的工具调用数就是可以超出上限的最大幅度。该上限同时是清理手段：
 * 本类不引入调度线程，也不需要 turn 结束信号即可自我约束。
 */
@Component
public class ToolCallStateGuard {

    /** 每个 turn 最多保留的终态记录条数（RUNNING 记录不占这个额度，也不会被淘汰）。 */
    public static final int MAX_CALLS_PER_TURN = 512;

    /**
     * 守卫自身的中断状态标记：不属于 {@code ToolResultContract} 的模型可见状态词表，
     * 只用于把「调用被取消/中断」这一异常出口也记入终态，与失败终态区分开。
     */
    public static final String INTERRUPTED = "interrupted";

    /** 工具调用生命周期状态。 */
    public enum ToolCallState {
        /** 已开始、未结束。 */
        RUNNING,
        /** 成功终态（{@code ok|queued|partial}）。 */
        SUCCESS,
        /** 失败终态（{@code error}）。 */
        ERROR,
        /** 被拒绝终态（{@code rejected}）。 */
        DENIED,
        /** 等待用户确认终态（{@code needs_confirmation}）。 */
        NEEDS_CONFIRMATION,
        /** 中断终态。 */
        INTERRUPTED;

        /** 是否为终态（终态不可再前进）。 */
        public boolean terminal() {
            return this != RUNNING;
        }
    }

    private final Map<String, Map<String, ToolCallState>> turns = new ConcurrentHashMap<>();

    /**
     * 记录一次工具调用开始执行。只应在工具真正执行前调用。
     *
     * @param turnId     所属 turn；可为空（空则与空 turn 归为一桶）
     * @param toolCallId 工具调用 ID；为空时整体跳过守卫
     * @throws IllegalStateException 同一 {@code (turnId, toolCallId)} 已经在执行或已到终态
     */
    public void begin(String turnId, String toolCallId) {
        if (skip(toolCallId)) return;
        Map<String, ToolCallState> calls = bucket(turnId);
        synchronized (calls) {
            ToolCallState existing = calls.get(toolCallId);
            if (existing != null) {
                throw new IllegalStateException(message("begin", turnId, toolCallId, existing, ToolCallState.RUNNING));
            }
            calls.put(toolCallId, ToolCallState.RUNNING);
            evict(calls);
        }
    }

    /**
     * 把调用推进到终态。同一调用只能成功推进一次。
     *
     * @param turnId       所属 turn
     * @param toolCallId   工具调用 ID；为空时整体跳过守卫
     * @param resultStatus {@code ToolResultContract} 规范化状态（{@code ok|queued|needs_confirmation|rejected|partial|error}）
     * @return 推进前的状态，正常单次完成路径恒为 {@link ToolCallState#RUNNING}
     * @throws IllegalStateException 未 {@code begin}、已经到过终态、或状态词表外的 {@code resultStatus}
     */
    public ToolCallState complete(String turnId, String toolCallId, String resultStatus) {
        ToolCallState target = terminalStateOf(resultStatus);
        if (skip(toolCallId)) return target;
        Map<String, ToolCallState> calls = bucket(turnId);
        synchronized (calls) {
            ToolCallState existing = calls.get(toolCallId);
            if (existing == null) {
                throw new IllegalStateException(
                        message("complete", turnId, toolCallId, null, target)
                                + "（该调用没有 begin 记录，重复或越权的完成写入已被拒绝）");
            }
            if (existing.terminal()) {
                throw new IllegalStateException(message("complete", turnId, toolCallId, existing, target));
            }
            calls.put(toolCallId, target);
            evict(calls);
            return existing;
        }
    }

    /**
     * 迟到写入断言：调用已经到过终态时抛异常，供结果重放/迟到回调等可疑路径诊断使用。
     *
     * @param turnId     所属 turn
     * @param toolCallId 工具调用 ID；为空时整体跳过
     * @throws IllegalStateException 该调用已经处于终态
     */
    public void assertNoLateWrite(String turnId, String toolCallId) {
        if (skip(toolCallId)) return;
        Map<String, ToolCallState> calls = bucket(turnId);
        synchronized (calls) {
            ToolCallState existing = calls.get(toolCallId);
            if (existing != null && existing.terminal()) {
                throw new IllegalStateException(
                        "工具调用终态之后出现迟到写入：turnId=" + text(turnId) + " toolCallId=" + toolCallId
                                + " 已是终态 " + existing);
            }
        }
    }

    /**
     * 只读诊断：查询某次调用的当前状态。
     *
     * @param turnId     所属 turn
     * @param toolCallId 工具调用 ID
     * @return 当前状态；没有记录时为 {@code null}（已完成且被淘汰的记录同样返回 {@code null}）
     */
    public ToolCallState state(String turnId, String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) return null;
        Map<String, ToolCallState> calls = turns.get(text(turnId));
        if (calls == null) return null;
        synchronized (calls) {
            return calls.get(toolCallId);
        }
    }

    /**
     * 只读诊断：查询某次调用的当前状态名。
     *
     * @param turnId     所属 turn
     * @param toolCallId 工具调用 ID
     * @return 状态名；没有记录时为 {@code null}
     */
    public String stateOf(String turnId, String toolCallId) {
        ToolCallState state = state(turnId, toolCallId);
        return state == null ? null : state.name();
    }

    /**
     * 清理某个 turn 的全部调用状态（turn 结束时调用，防止跨轮残留）。
     *
     * @param turnId 要清理的 turn
     */
    public void clear(String turnId) {
        turns.remove(text(turnId));
    }

    /** 清理全部 turn 的调用状态（测试与紧急复位用）。 */
    public void clearAll() {
        turns.clear();
    }

    /**
     * 只读快照：当前保留的 {@code turnId -> {toolCallId -> 状态名}} 副本，供测试与诊断断言。
     *
     * @return 不可变快照，与内部状态解耦
     */
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        List<String> turnIds = new ArrayList<>(turns.keySet());
        turnIds.sort(String::compareTo);
        for (String turnId : turnIds) {
            Map<String, ToolCallState> calls = turns.get(turnId);
            if (calls == null) continue;
            Map<String, String> copy = new LinkedHashMap<>();
            synchronized (calls) {
                calls.forEach((callId, state) -> copy.put(callId, state.name()));
            }
            out.put(turnId, copy);
        }
        return Map.copyOf(out);
    }

    /** 当前保留状态的调用总数（含终态记录），供测试断言内存边界。 */
    public int size() {
        int total = 0;
        for (Map<String, ToolCallState> calls : turns.values()) {
            synchronized (calls) {
                total += calls.size();
            }
        }
        return total;
    }

    /**
     * {@code ToolResultContract} 状态到生命周期终态的映射。
     *
     * @param resultStatus 契约状态；{@link #INTERRUPTED} 是守卫自身的中断标记
     * @return 对应的终态（永不返回 {@link ToolCallState#RUNNING}）
     * @throws IllegalStateException 状态为空或在词表之外
     */
    static ToolCallState terminalStateOf(String resultStatus) {
        if (resultStatus == null || resultStatus.isBlank()) {
            throw new IllegalStateException("工具调用生命周期守卫：resultStatus 为空，无法判定终态，已显式拒绝。");
        }
        return switch (resultStatus.trim()) {
            case ToolResultContract.OK, ToolResultContract.QUEUED, ToolResultContract.PARTIAL ->
                    ToolCallState.SUCCESS;
            case ToolResultContract.ERROR -> ToolCallState.ERROR;
            case ToolResultContract.REJECTED -> ToolCallState.DENIED;
            case ToolResultContract.NEEDS_CONFIRMATION -> ToolCallState.NEEDS_CONFIRMATION;
            case INTERRUPTED -> ToolCallState.INTERRUPTED;
            default -> throw new IllegalStateException(
                    "工具调用生命周期守卫：resultStatus=" + resultStatus
                            + " 不在契约词表内（ok|queued|partial|error|rejected|needs_confirmation），已显式拒绝。");
        };
    }

    private Map<String, ToolCallState> bucket(String turnId) {
        return turns.computeIfAbsent(text(turnId), key -> new LinkedHashMap<>());
    }

    /**
     * 桶内终态记录超出上限时从最旧开始淘汰，RUNNING 记录永不淘汰。
     *
     * <p>不变式：淘汰后桶内终态记录数 ≤ {@link #MAX_CALLS_PER_TURN}，因此
     * {@code size() ≤ MAX_CALLS_PER_TURN + 仍在执行的调用数}。
     */
    private static void evict(Map<String, ToolCallState> calls) {
        List<String> removable = new ArrayList<>(calls.size());
        calls.forEach((callId, state) -> {
            if (state.terminal()) removable.add(callId);
        });
        int overflow = removable.size() - MAX_CALLS_PER_TURN;
        for (String callId : removable) {
            if (overflow-- <= 0) break;
            calls.remove(callId);
        }
    }

    private static boolean skip(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank();
    }

    private static String text(String turnId) {
        return turnId == null ? "" : turnId;
    }

    private static String message(String action, String turnId, String toolCallId,
                                 ToolCallState from, ToolCallState to) {
        return "工具调用生命周期越界：" + action
                + " turnId=" + text(turnId)
                + " toolCallId=" + toolCallId
                + " from=" + (from == null ? "NONE" : from.name())
                + " to=" + to.name();
    }
}
