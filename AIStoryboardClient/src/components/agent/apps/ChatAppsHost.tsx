import { useCallback, useEffect, useRef } from 'react';
import { EmbeddedAppWindow } from './EmbeddedAppWindow';
import { MinimizedAppsLauncher } from './MinimizedAppsLauncher';
import { useAgentStore } from '../../../stores/agentStore';
import { useDocumentPreviewStore } from '../../../stores/documentPreviewStore';
import { useSideChatSelectionStore } from '../../../stores/sideChatSelectionStore';
import {
  CANVAS_GRAPH_UPDATED_MESSAGE,
  SIDE_CHAT_CONTEXT_MESSAGE,
  buildSceneInspectPrompt,
  isCanvasGraphPatch,
  isCanvasModeReadyMessage,
  isCanvasModeStatusMessage,
  isSideChatContextRequestMessage,
  isSideChatContextUpdateMessage,
  isStoryboardReferenceMessage,
  type CanvasGraphPatch,
  type CanvasModeReadyMessage,
  type CanvasModeStatusMessage,
} from '../../../lib/embeddedAppMessages';
import { CANVAS_MODE_CONTROL_MESSAGE } from '../../../lib/embeddedAppMessages';
import type { ChatAppsController } from './useChatApps';

/**
 * 从 canvas-run 消息 metadata 取增量补丁。
 *
 * <p>字段由 MoonAgent 写入（`canvasPatch` / `canvasPatchOverflow`）；metadata 类型定义在
 * `stores/agentStore` + `api/agent`，这里按 unknown 读取，避免跨战线改类型定义。
 * 超限或结构不合法时返回 undefined → 只发 revision，画布整图重载兜底。
 *
 * <p>导出仅为单测覆盖「取哪份补丁」的判定（组件测试需要真 iframe，成本过高）。
 */
export function canvasPatchFromMetadata(metadata: unknown): CanvasGraphPatch | undefined {
  if (!metadata || typeof metadata !== 'object') return undefined;
  const record = metadata as { canvasPatch?: unknown; canvasPatchOverflow?: unknown };
  if (record.canvasPatchOverflow === true) return undefined;
  return isCanvasGraphPatch(record.canvasPatch) ? record.canvasPatch : undefined;
}

export function ChatAppsHost({
  apps,
  currentProjectId,
  docked,
  splitMaxWidth,
  expandedSplitMaxWidth,
  sidebarCollapsed,
  sidebarCollapseWidth,
  onCollapseSidebar,
  onRequestDock,
  onCanvasModeStatus,
  onCanvasModeReady,
  canvasInteractionLocked = false,
}: {
  apps: ChatAppsController;
  currentProjectId?: string;
  docked: boolean;
  splitMaxWidth: number;
  expandedSplitMaxWidth: number;
  sidebarCollapsed: boolean;
  sidebarCollapseWidth: number;
  onCollapseSidebar: () => void;
  onRequestDock: () => void;
  onCanvasModeStatus?: (message: CanvasModeStatusMessage) => void;
  onCanvasModeReady?: (message: CanvasModeReadyMessage) => void;
  canvasInteractionLocked?: boolean;
}) {
  const messages = useAgentStore((state) => state.messages);
  const closeSideChat = useAgentStore((state) => state.closeSideChat);
  const closeDocumentPreview = useDocumentPreviewStore((state) => state.closeDocument);
  const sideChatSelections = useSideChatSelectionStore((state) => state.selections);
  const replaceSideChatSelections = useSideChatSelectionStore((state) => state.replaceSelections);
  const postedCanvasRevisionsRef = useRef<Record<string, number>>({});
  const setAppLayout = apps.setLayout;

  const handleLayoutChange = useCallback((layout: {
    width: number;
    height: number;
    fullscreen: boolean;
  }) => {
    setAppLayout((current) => (
      current.width === layout.width
      && current.height === layout.height
      && current.fullscreen === layout.fullscreen
        ? current
        : { ...current, ...layout }
    ));
  }, [setAppLayout]);

  const closeTab = useCallback((key: Parameters<typeof apps.close>[0]) => {
    if (key === 'document') closeDocumentPreview();
    if (key === 'side-chat') void closeSideChat();
    apps.close(key);
  }, [apps, closeDocumentPreview, closeSideChat]);

  const closeWindow = useCallback(() => {
    closeDocumentPreview();
    void closeSideChat();
    apps.closeAll();
  }, [apps, closeDocumentPreview, closeSideChat]);

  useEffect(() => {
    const sideChatTab = apps.tabs.find((tab) => tab.key === 'side-chat');
    if (!sideChatTab) return;
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="side-chat"]');
    if (!frame?.contentWindow) return;
    let conversationId = '';
    try {
      conversationId = new URL(sideChatTab.url, window.location.origin)
        .searchParams.get('sideChatId') ?? '';
    } catch {
      return;
    }
    if (!conversationId) return;
    const post = () => frame.contentWindow?.postMessage({
      type: SIDE_CHAT_CONTEXT_MESSAGE,
      conversationId,
      selections: sideChatSelections,
    }, window.location.origin);
    post();
    frame.addEventListener('load', post);
    return () => frame.removeEventListener('load', post);
  }, [apps.tabs, sideChatSelections]);

  useEffect(() => {
    const sideChatTab = apps.tabs.find((tab) => tab.key === 'side-chat');
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="side-chat"]');
    if (!sideChatTab || !frame?.contentWindow) return;
    let conversationId = '';
    try {
      conversationId = new URL(sideChatTab.url, window.location.origin)
        .searchParams.get('sideChatId') ?? '';
    } catch {
      return;
    }
    if (!conversationId) return;
    const onMessage = (event: MessageEvent) => {
      if (event.source !== frame.contentWindow) return;
      if (isSideChatContextUpdateMessage(event.data)) {
        replaceSideChatSelections(event.data.selections);
        return;
      }
      if (isSideChatContextRequestMessage(event.data)
        && event.data.conversationId === conversationId) {
        frame.contentWindow?.postMessage({
          type: SIDE_CHAT_CONTEXT_MESSAGE,
          conversationId,
          selections: sideChatSelections,
        }, window.location.origin);
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [apps.tabs, replaceSideChatSelections, sideChatSelections]);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const sourceMatches = Array.from(document.querySelectorAll('iframe'))
        .some((frame) => frame.contentWindow === event.source);
      if (!sourceMatches) return;
      if (event.origin === window.location.origin && isStoryboardReferenceMessage(event.data)) {
        if (!currentProjectId || event.data.projectId !== currentProjectId) return;
        void useAgentStore.getState().sendMessage(
          buildSceneInspectPrompt(event.data.scenes),
          { routingHint: 'intent-storyboard' },
        );
        return;
      }
      const canvasTab = apps.tabs.find((tab) => tab.key === 'canvas');
      const canvasBase = (import.meta.env.VITE_CANVAS_URL as string | undefined)?.trim().replace(/\/$/, '')
        || canvasTab?.url
        || '/canvas/';
      let canvasOrigin = '';
      try { canvasOrigin = new URL(canvasBase).origin; } catch { /* invalid config falls through */ }
      if (!canvasOrigin || event.origin !== canvasOrigin) return;
      if (isCanvasModeStatusMessage(event.data)) onCanvasModeStatus?.(event.data);
      if (isCanvasModeReadyMessage(event.data)) onCanvasModeReady?.(event.data);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [apps.tabs, currentProjectId, onCanvasModeReady, onCanvasModeStatus]);

  useEffect(() => {
    const canvasTab = apps.tabs.find((tab) => tab.key === 'canvas' && tab.canvasMode);
    if (!canvasTab?.canvasMode) return;
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="canvas"]');
    if (!frame?.contentWindow) return;
    let origin = '';
    try { origin = new URL(canvasTab.url, window.location.origin).origin; } catch { return; }
    const send = () => frame.contentWindow?.postMessage({
      type: CANVAS_MODE_CONTROL_MESSAGE,
      conversationId: canvasTab.canvasMode!.conversationId,
      projectId: canvasTab.canvasMode!.projectId,
      locked: canvasInteractionLocked,
    }, origin);
    send();
    frame.addEventListener('load', send);
    return () => frame.removeEventListener('load', send);
  }, [apps.tabs, canvasInteractionLocked]);

  useEffect(() => {
    const canvasTab = apps.tabs.find((tab) => tab.key === 'canvas' && tab.canvasMode);
    const canvasMode = canvasTab?.canvasMode;
    if (!canvasMode?.canvasId) {
      // 诊断：画布 iframe 存在但没有 canvasMode 绑定时，图更新根本发不出去——
      // 画布侧只能靠自己 4s 轮询，用户会感觉"必须刷新"。这一行让两个 console 都能看出断点在哪。
      if (apps.tabs.some((tab) => tab.key === 'canvas')) {
        console.info('[canvas-live] 画布标签未绑定画布模式，跳过图更新转发');
      }
      return;
    }
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="canvas"]');
    if (!frame?.contentWindow) {
      console.info('[canvas-live] 未找到画布 iframe，跳过图更新转发');
      return;
    }
    const latest = messages
      .filter((message) =>
        message.metadata?.kind === 'canvas-run'
        && message.conversationId === canvasMode.conversationId
        && message.metadata.canvasId === canvasMode.canvasId
        && typeof message.metadata.revision === 'number')
      .sort((left, right) => (right.metadata?.revision ?? -1) - (left.metadata?.revision ?? -1))[0];
    const revision = latest?.metadata?.revision;
    if (revision === undefined) {
      console.info(`[canvas-live] 暂无匹配的 canvas-run 消息（conversationId=${canvasMode.conversationId} `
        + `canvasId=${canvasMode.canvasId}），跳过转发`);
      return;
    }
    const key = `${canvasMode.conversationId}:${canvasMode.canvasId}`;
    if ((postedCanvasRevisionsRef.current[key] ?? -1) >= revision) return;
    if (!canvasTab) return;
    let origin = '';
    try { origin = new URL(canvasTab.url, window.location.origin).origin; } catch { return; }
    // 累积超限（canvasPatchOverflow）时只发 revision：画布退回整图重载兜底。
    const patch = canvasPatchFromMetadata(latest?.metadata);
    postedCanvasRevisionsRef.current[key] = revision;
    console.info(`[canvas-live] 转发画布更新 revision=${revision} patch=${patch ? 'yes' : 'no'}`);
    frame.contentWindow.postMessage({
      type: CANVAS_GRAPH_UPDATED_MESSAGE,
      conversationId: canvasMode.conversationId,
      projectId: canvasMode.projectId,
      canvasId: canvasMode.canvasId,
      revision,
      ...(patch ? { patch } : {}),
    }, origin);
  }, [apps.tabs, messages]);

  if (!apps.hasTabs || !apps.activeKey) return null;

  return (
    <>
      {apps.layout.minimized && (
        <MinimizedAppsLauncher
          tabs={apps.tabs}
          activeKey={apps.activeKey}
          onRestore={() => apps.setLayout((current) => ({ ...current, minimized: false }))}
          onCloseAll={closeWindow}
        />
      )}
      <EmbeddedAppWindow
        tabs={apps.tabs}
        activeKey={apps.activeKey}
        onSelectTab={apps.select}
        onCloseTab={closeTab}
        onCloseWindow={closeWindow}
        onMinimize={() => apps.setLayout((current) => ({ ...current, minimized: true }))}
        docked={docked}
        splitMaxWidth={splitMaxWidth}
        expandedSplitMaxWidth={expandedSplitMaxWidth}
        sidebarCollapsed={sidebarCollapsed}
        sidebarCollapseWidth={sidebarCollapseWidth}
        onCollapseSidebar={onCollapseSidebar}
        onRequestDock={onRequestDock}
        onLayoutChange={handleLayoutChange}
        minimized={apps.layout.minimized}
      />
    </>
  );
}
