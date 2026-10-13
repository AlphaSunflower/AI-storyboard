package com.moon.xiaoyuecanvas.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * 浏览器画布命令信箱（原 canvas-bridge 独立进程的核心逻辑）。
 *
 * <p>agent 经 MCP 投递命令 → 浏览器轮询领取并在页面内执行 → 结果回传兑现等待中的调用。
 * 执行层始终在浏览器里，这里只做标签注册表、排队、超时与结果兑现。
 *
 * <p>标签按 {@code 用户 + 项目 + 画布} 归属，避免跨用户驱动别人的页面；状态是单实例内存态
 * （与旧 Node 版一致），进程重启后由浏览器的重连循环自动重注册。
 */
@Component
public class CanvasBrowserMailbox {

    public static final int PROTOCOL_VERSION = 1;
    /** 命令等待浏览器回传的上限（与旧 bridge 的 BRIDGE_COMMAND_TIMEOUT_MS 一致）。 */
    public static final long COMMAND_TIMEOUT_MS = 11 * 60_000L;
    /** 超过该时间未轮询即视为页面不再响应命令。 */
    public static final long BROWSER_HEARTBEAT_TIMEOUT_MS = 30_000L;
    /** 超过该时间未轮询即视为标签已失活（不再作为命令目标）。 */
    public static final long TAB_STALE_MS = 15 * 60_000L;

    /** 确认类命令只能由服务端强制预览，调用方不能通过 dryRun=false 绕过。 */
    private static final Set<String> FORCED_DRY_RUN_METHODS = Set.of(
            "canvas.node.delete",
            "canvas.edge.delete",
            "canvas.node.execute",
            "canvas.graph.execute");

    private static final long ACTIVE_TAB_GRACE_MS = 5_000L;
    private static final int PENDING_WARN_THRESHOLD = 20;
    private static final String RESOLUTION_ERROR_PREFIX = "error|";
    private static final String TIMEOUT_MESSAGE = "浏览器未在超时时间内响应命令";

    private static final Logger log = LoggerFactory.getLogger(CanvasBrowserMailbox.class);

    private final Map<String, TabState> tabs = new ConcurrentHashMap<>();
    private final Map<String, String> activeTabByCanvas = new ConcurrentHashMap<>();
    private final Map<String, Deque<PendingCommand>> pendingByTab = new ConcurrentHashMap<>();
    private final Map<String, PendingCommand> pendingById = new ConcurrentHashMap<>();

    /** 时间源，测试可替换以便验证失活/响应性判定，避免真等 15 分钟。 */
    private volatile LongSupplier clock = System::currentTimeMillis;

    public static boolean requiresServerDryRun(String method) {
        return method != null && FORCED_DRY_RUN_METHODS.contains(method);
    }

    void useClock(LongSupplier replacement) {
        this.clock = replacement == null ? System::currentTimeMillis : replacement;
    }

    private long now() {
        return clock.getAsLong();
    }

    /** 浏览器打开画布后注册当前标签页。 */
    public synchronized Map<String, Object> register(String userId,
                                                     String tabId,
                                                     String projectId,
                                                     String projectTitle,
                                                     String canvasId,
                                                     String canvasTitle) {
        if (isBlank(userId) || isBlank(tabId) || isBlank(projectId) || isBlank(canvasId)) {
            return failure("INVALID_ARGUMENTS", "缺少 tabId、projectId 或 canvasId");
        }
        TabState previous = tabs.get(tabId);
        if (previous != null) {
            activeTabByCanvas.remove(canvasKey(previous.userId, previous.projectId, previous.canvasId));
        }
        TabState tab = new TabState(
                tabId,
                userId,
                projectId,
                trimToDefault(projectTitle, "未命名项目"),
                canvasId,
                trimToDefault(canvasTitle, "未命名画布"),
                now());
        tabs.put(tabId, tab);

        String key = canvasKey(userId, projectId, canvasId);
        String activeTabId = activeTabByCanvas.get(key);
        TabState active = activeTabId == null ? null : tabs.get(activeTabId);
        if (active == null || now() - active.lastSeen > ACTIVE_TAB_GRACE_MS) {
            activeTabByCanvas.put(key, tabId);
        }
        return success();
    }

    /** 浏览器离开画布时注销标签页。 */
    public synchronized void unregister(String userId, String tabId) {
        TabState tab = tabs.get(tabId);
        if (tab == null || !tab.userId.equals(userId)) return;
        tabs.remove(tabId);
        String key = canvasKey(tab.userId, tab.projectId, tab.canvasId);
        if (tabId.equals(activeTabByCanvas.get(key))) {
            TabState fallback = tabs.values().stream()
                    .filter(candidate -> candidate.userId.equals(tab.userId)
                            && candidate.projectId.equals(tab.projectId)
                            && candidate.canvasId.equals(tab.canvasId))
                    .max((left, right) -> Long.compare(left.lastSeen, right.lastSeen))
                    .orElse(null);
            if (fallback != null) activeTabByCanvas.put(key, fallback.tabId);
            else activeTabByCanvas.remove(key);
        }
        pendingByTab.remove(tabId);
    }

    /** 浏览器侧就绪探测：返回该用户当前打开的活跃画布。 */
    public synchronized Map<String, Object> health(String userId) {
        List<Map<String, Object>> projects = new ArrayList<>();
        for (TabState tab : activeTabs(userId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("projectId", tab.projectId);
            entry.put("projectTitle", tab.projectTitle);
            entry.put("canvasId", tab.canvasId);
            entry.put("canvasTitle", tab.canvasTitle);
            entry.put("lastSeen", tab.lastSeen);
            entry.put("responsive", isResponsive(tab));
            projects.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", PROTOCOL_VERSION);
        out.put("projects", projects);
        return out;
    }

    /** 浏览器轮询领取一条待执行命令；无命令时返回空对象。 */
    public synchronized Map<String, Object> poll(String userId, String tabId) {
        TabState tab = tabs.get(tabId);
        if (tab == null || !tab.userId.equals(userId)) return new LinkedHashMap<>();
        tab.lastSeen = now();
        Deque<PendingCommand> queue = pendingByTab.get(tabId);
        if (queue == null) return new LinkedHashMap<>();
        PendingCommand command = queue.pollFirst();
        if (command == null) pendingByTab.remove(tabId);
        return command == null ? new LinkedHashMap<>() : command.toPollResult();
    }

    /** 浏览器回传命令执行结果。 */
    public synchronized void result(String userId, String requestId, Map<String, Object> response) {
        PendingCommand pending = requestId == null ? null : pendingById.get(requestId);
        if (pending == null || !pending.userId.equals(userId)) return;
        settle(pending, response);
    }

    /**
     * 投递一条画布命令并等待浏览器回传结果。
     *
     * <p>等待不在锁内进行：命令超时上限 11 分钟，持锁会阻塞其它标签的轮询。
     */
    public Map<String, Object> command(String userId,
                                       String projectId,
                                       String canvasId,
                                       String method,
                                       Map<String, Object> params,
                                       boolean dryRun,
                                       Long expectedRevision) {
        boolean effectiveDryRun = dryRun || requiresServerDryRun(method);
        PendingCommand pending;
        synchronized (this) {
            String resolved = resolveTab(userId, projectId, canvasId);
            if (resolved == null || resolved.startsWith(RESOLUTION_ERROR_PREFIX)) {
                return failure(resolutionCode(resolved), resolutionMessage(resolved));
            }
            pending = new PendingCommand(
                    UUID.randomUUID().toString(), resolved, userId, method, params,
                    effectiveDryRun, expectedRevision);
            pendingById.put(pending.requestId, pending);
            pendingByTab.computeIfAbsent(resolved, key -> new ArrayDeque<>()).addLast(pending);
            if (pendingById.size() > PENDING_WARN_THRESHOLD) {
                log.warn("[canvas-mailbox] 待回传命令堆积 {} 条，检查画布页面是否在正常轮询", pendingById.size());
            }
        }
        try {
            Map<String, Object> response = pending.future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return response == null ? failure("BRIDGE_TIMEOUT", TIMEOUT_MESSAGE) : response;
        } catch (TimeoutException timeout) {
            return failure("BRIDGE_TIMEOUT", TIMEOUT_MESSAGE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("BRIDGE_INTERRUPTED", "命令等待被中断");
        } catch (ExecutionException error) {
            String message = error.getCause() == null ? "画布命令失败" : String.valueOf(error.getCause());
            return failure("CANVAS_COMMAND_FAILED", message);
        } finally {
            settle(pending, null);
        }
    }

    /** 兑现等待中的命令并释放登记项；重复调用无副作用。 */
    private synchronized void settle(PendingCommand pending, Map<String, Object> response) {
        if (!pending.markSettled()) return;
        pendingById.remove(pending.requestId);
        pending.future.complete(response);
    }

    /** 解析命令目标标签：返回 tabId，或以 {@code error|} 前缀返回错误码与消息。 */
    private String resolveTab(String userId, String projectId, String canvasId) {
        if (!isBlank(canvasId) && isBlank(projectId)) {
            return resolutionError("INVALID_ARGUMENTS", "指定 canvasId 时必须同时指定 projectId");
        }
        if (!isBlank(projectId) && !isBlank(canvasId)) {
            String tabId = activeTabByCanvas.get(canvasKey(userId, projectId, canvasId));
            TabState tab = tabId == null ? null : tabs.get(tabId);
            if (tab == null || !isFresh(tab)) {
                return resolutionError("CANVAS_NOT_OPEN", "目标画布当前未在浏览器中打开");
            }
            return isResponsive(tab) ? tabId : notResponding();
        }
        if (!isBlank(projectId)) {
            List<TabState> matches = activeTabs(userId).stream()
                    .filter(tab -> tab.projectId.equals(projectId))
                    .toList();
            if (matches.isEmpty()) {
                return resolutionError("PROJECT_NOT_OPEN", "目标项目当前未在浏览器中打开");
            }
            if (matches.size() > 1) {
                return resolutionError("AMBIGUOUS_CANVAS", "该项目打开了多个画布，请指定 canvasId");
            }
            return isResponsive(matches.get(0)) ? matches.get(0).tabId : notResponding();
        }
        List<TabState> opened = activeTabs(userId);
        if (opened.isEmpty()) return resolutionError("PROJECT_NOT_OPEN", "没有打开的项目");
        if (opened.size() > 1) {
            return resolutionError("AMBIGUOUS_PROJECT", "多个画布已打开，请指定 projectId 和 canvasId");
        }
        return isResponsive(opened.get(0)) ? opened.get(0).tabId : notResponding();
    }

    private List<TabState> activeTabs(String userId) {
        List<TabState> out = new ArrayList<>();
        for (String tabId : activeTabByCanvas.values()) {
            TabState tab = tabs.get(tabId);
            if (tab == null || !isFresh(tab)) continue;
            if (userId != null && !tab.userId.equals(userId)) continue;
            out.add(tab);
        }
        return out;
    }

    private static String notResponding() {
        return resolutionError("BROWSER_NOT_RESPONDING",
                "目标画布已打开，但浏览器页面未在响应画布命令，请重新打开或刷新画布。");
    }

    private static String resolutionError(String code, String message) {
        return RESOLUTION_ERROR_PREFIX + code + "|" + message;
    }

    private static String resolutionCode(String resolved) {
        if (resolved == null || !resolved.startsWith(RESOLUTION_ERROR_PREFIX)) return "CANVAS_NOT_OPEN";
        return resolved.substring(RESOLUTION_ERROR_PREFIX.length()).split("\\|", 2)[0];
    }

    private static String resolutionMessage(String resolved) {
        if (resolved == null || !resolved.startsWith(RESOLUTION_ERROR_PREFIX)) {
            return "目标画布当前未在浏览器中打开";
        }
        String[] parts = resolved.substring(RESOLUTION_ERROR_PREFIX.length()).split("\\|", 2);
        return parts.length > 1 ? parts[1] : "目标画布当前未在浏览器中打开";
    }

    static Map<String, Object> failure(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "画布命令失败" : message);
        out.put("error", error);
        return out;
    }

    private static Map<String, Object> success() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private static String canvasKey(String userId, String projectId, String canvasId) {
        return userId + "|" + projectId + "|" + canvasId;
    }

    private boolean isFresh(TabState tab) {
        return now() - tab.lastSeen <= TAB_STALE_MS;
    }

    private boolean isResponsive(TabState tab) {
        return now() - tab.lastSeen <= BROWSER_HEARTBEAT_TIMEOUT_MS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static final class TabState {
        private final String tabId;
        private final String userId;
        private final String projectId;
        private final String projectTitle;
        private final String canvasId;
        private final String canvasTitle;
        private volatile long lastSeen;

        private TabState(String tabId,
                         String userId,
                         String projectId,
                         String projectTitle,
                         String canvasId,
                         String canvasTitle,
                         long lastSeen) {
            this.tabId = tabId;
            this.userId = userId;
            this.projectId = projectId;
            this.projectTitle = projectTitle;
            this.canvasId = canvasId;
            this.canvasTitle = canvasTitle;
            this.lastSeen = lastSeen;
        }
    }

    private static final class PendingCommand {
        private final String requestId;
        private final String tabId;
        private final String userId;
        private final String method;
        private final Map<String, Object> params;
        private final boolean dryRun;
        private final Long expectedRevision;
        private final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        private boolean settled;

        private PendingCommand(String requestId,
                               String tabId,
                               String userId,
                               String method,
                               Map<String, Object> params,
                               boolean dryRun,
                               Long expectedRevision) {
            this.requestId = requestId;
            this.tabId = tabId;
            this.userId = userId;
            this.method = method;
            this.params = params == null ? Map.of() : params;
            this.dryRun = dryRun;
            this.expectedRevision = expectedRevision;
        }

        private synchronized boolean markSettled() {
            if (settled) return false;
            settled = true;
            return true;
        }

        private Map<String, Object> toPollResult() {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("requestId", requestId);
            command.put("method", method);
            command.put("params", params);
            command.put("source", "mcp");
            command.put("dryRun", dryRun);
            if (expectedRevision != null) command.put("expectedRevision", expectedRevision);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", command);
            return out;
        }
    }
}
