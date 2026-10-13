package com.moon.xiaoyuecanvas.bridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasBrowserMailboxTest {

    private static final String USER = "user-1";
    private static final String PROJECT = "project-1";
    private static final String CANVAS = "canvas-1";
    private static final String TAB = "tab-1";

    private static long clock = 1_000_000L;

    private static CanvasBrowserMailbox mailboxAtFixedClock() {
        clock = 1_000_000L;
        CanvasBrowserMailbox mailbox = new CanvasBrowserMailbox();
        mailbox.useClock(() -> clock);
        return mailbox;
    }

    private static void registerDefault(CanvasBrowserMailbox mailbox) {
        mailbox.register(USER, TAB, PROJECT, "项目 A", CANVAS, "画布 A");
    }

    private static Map<String, Object> commandWithBrowserReply(CanvasBrowserMailbox mailbox,
                                                               String userId,
                                                               String projectId,
                                                               String canvasId,
                                                               String tabId,
                                                               String method,
                                                               Map<String, Object> params,
                                                               boolean dryRun,
                                                               Map<String, Object> browserResponse) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(
                    () -> mailbox.command(userId, projectId, canvasId, method, params, dryRun, null));
            Map<String, Object> polled = awaitPolledCommand(mailbox, userId, tabId);
            String requestId = String.valueOf(commandOf(polled).get("requestId"));
            mailbox.result(userId, requestId, browserResponse);
            return pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> commandOf(Map<String, Object> polled) {
        return (Map<String, Object>) polled.get("command");
    }

    private static Map<String, Object> awaitPolledCommand(CanvasBrowserMailbox mailbox,
                                                          String userId,
                                                          String tabId) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            Map<String, Object> polled = mailbox.poll(userId, tabId);
            if (polled.containsKey("command")) return polled;
            Thread.sleep(10);
        }
        throw new AssertionError("命令没有进入队列");
    }

    private static Map<String, Object> okResponse(Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> errorOf(Map<String, Object> payload) {
        assertEquals(false, payload.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        return error;
    }

    @Test
    void routesCommandToTheExactProjectAndCanvasTab() throws Exception {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);
        mailbox.register(USER, "tab-2", PROJECT, "项目 A", "canvas-2", "画布 B");

        Map<String, Object> response = commandWithBrowserReply(
                mailbox, USER, PROJECT, CANVAS, TAB, "canvas.node.execute",
                Map.of("nodeId", "n1"), false, okResponse(Map.of("jobId", "job-7")));

        assertEquals(true, response.get("ok"));
        assertEquals(Map.of("jobId", "job-7"), response.get("result"));
    }

    @Test
    void keepsCommandsInsideTheirOwnUser() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);

        Map<String, Object> response = mailbox.command(
                "user-2", PROJECT, CANVAS, "canvas.node.execute", Map.of("nodeId", "n1"), false, null);

        assertEquals("CANVAS_NOT_OPEN", errorOf(response).get("code"));
    }

    @Test
    void reportsCanvasNotOpenWhenNoTabIsRegistered() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();

        Map<String, Object> response = mailbox.command(
                USER, PROJECT, CANVAS, "canvas.graph.execute", Map.of(), false, null);

        assertEquals("CANVAS_NOT_OPEN", errorOf(response).get("code"));
    }

    @Test
    void reportsAmbiguousCanvasWhenProjectHasSeveralOpenCanvases() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);
        mailbox.register(USER, "tab-2", PROJECT, "项目 A", "canvas-2", "画布 B");

        Map<String, Object> response = mailbox.command(
                USER, PROJECT, null, "canvas.execution.get", Map.of("executionId", "e1"), false, null);

        assertEquals("AMBIGUOUS_CANVAS", errorOf(response).get("code"));
    }

    @Test
    void forcesServerSideDryRunForDestructiveCommands() throws Exception {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(() -> mailbox.command(
                    USER, PROJECT, CANVAS, "canvas.graph.execute", Map.of(), false, null));
            Map<String, Object> command = commandOf(awaitPolledCommand(mailbox, USER, TAB));
            assertEquals(true, command.get("dryRun"));
            mailbox.result(USER, String.valueOf(command.get("requestId")), okResponse(Map.of()));
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(CanvasBrowserMailbox.requiresServerDryRun("canvas.node.execute"));
        assertFalse(CanvasBrowserMailbox.requiresServerDryRun("canvas.image.crop"));
    }

    @Test
    void keepsCallerPreviewFlagForHarmlessCommands() throws Exception {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(() -> mailbox.command(
                    USER, PROJECT, CANVAS, "canvas.undo", Map.of(), true, null));
            Map<String, Object> command = commandOf(awaitPolledCommand(mailbox, USER, TAB));
            assertEquals(true, command.get("dryRun"));
            mailbox.result(USER, String.valueOf(command.get("requestId")), okResponse(Map.of()));
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fallsBackToAnotherTabWhenTheActiveTabUnregisters() throws Exception {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);
        mailbox.register(USER, "tab-2", PROJECT, "项目 A", CANVAS, "画布 A");
        mailbox.unregister(USER, TAB);

        Map<String, Object> response = commandWithBrowserReply(
                mailbox, USER, PROJECT, CANVAS, "tab-2", "canvas.image.crop",
                Map.of("nodeId", "n1"), false, okResponse(Map.of("nodeId", "n9")));

        assertEquals(true, response.get("ok"));
        assertEquals(Map.of("nodeId", "n9"), response.get("result"));
    }

    @Test
    void ignoresResultPostedByAnotherUser() throws Exception {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(() -> mailbox.command(
                    USER, PROJECT, CANVAS, "canvas.node.execute", Map.of("nodeId", "n1"), false, null));
            Map<String, Object> command = commandOf(awaitPolledCommand(mailbox, USER, TAB));
            String requestId = String.valueOf(command.get("requestId"));

            mailbox.result("user-2", requestId, okResponse(Map.of("jobId", "forged")));
            assertFalse(pending.isDone(), "其它用户的回传不应兑现命令");

            mailbox.result(USER, requestId, okResponse(Map.of("jobId", "real")));
            Map<String, Object> response = pending.get(5, TimeUnit.SECONDS);
            assertEquals(Map.of("jobId", "real"), response.get("result"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reportsBrowserNotRespondingAfterHeartbeatWindow() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);
        clock += CanvasBrowserMailbox.BROWSER_HEARTBEAT_TIMEOUT_MS + 1;

        Map<String, Object> response = mailbox.command(
                USER, PROJECT, CANVAS, "canvas.node.execute", Map.of("nodeId", "n1"), false, null);

        assertEquals("BROWSER_NOT_RESPONDING", errorOf(response).get("code"));
        assertEquals(false, onlyProject(mailbox).get("responsive"));
    }

    @Test
    void dropsTabsThatWentStale() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);
        clock += CanvasBrowserMailbox.TAB_STALE_MS + 1;

        Map<String, Object> response = mailbox.command(
                USER, PROJECT, CANVAS, "canvas.node.execute", Map.of("nodeId", "n1"), false, null);

        assertEquals("CANVAS_NOT_OPEN", errorOf(response).get("code"));
        assertTrue(projectsOf(mailbox).isEmpty(), "失活标签不应出现在健康清单里");
    }

    @Test
    void pollRefreshesHeartbeatAndRequiresOwningUser() {
        CanvasBrowserMailbox mailbox = mailboxAtFixedClock();
        registerDefault(mailbox);

        assertTrue(mailbox.poll("user-2", TAB).isEmpty(), "非本人标签不应被轮询");

        clock += CanvasBrowserMailbox.BROWSER_HEARTBEAT_TIMEOUT_MS - 1;
        assertTrue(mailbox.poll(USER, TAB).isEmpty());
        clock += 2;

        assertEquals(true, onlyProject(mailbox).get("responsive"), "轮询应刷新心跳时间");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectsOf(CanvasBrowserMailbox mailbox) {
        return (List<Map<String, Object>>) mailbox.health(USER).get("projects");
    }

    private static Map<String, Object> onlyProject(CanvasBrowserMailbox mailbox) {
        List<Map<String, Object>> projects = projectsOf(mailbox);
        assertEquals(1, projects.size(), "期望恰好一个活跃画布");
        return projects.get(0);
    }
}
