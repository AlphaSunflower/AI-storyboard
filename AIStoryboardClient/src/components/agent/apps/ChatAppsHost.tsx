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
  isCanvasModeReadyMessage,
  isCanvasModeStatusMessage,
  isSideChatContextRequestMessage,
  isSideChatContextUpdateMessage,
  isStoryboardReferenceMessage,
  type CanvasModeReadyMessage,
  type CanvasModeStatusMessage,
} from '../../../lib/embeddedAppMessages';
import { CANVAS_MODE_CONTROL_MESSAGE } from '../../../lib/embeddedAppMessages';
import type { ChatAppsController } from './useChatApps';

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
    if (!canvasMode?.canvasId) return;
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="canvas"]');
    if (!frame?.contentWindow) return;
    const latest = messages
      .filter((message) =>
        message.metadata?.kind === 'canvas-run'
        && message.conversationId === canvasMode.conversationId
        && message.metadata.canvasId === canvasMode.canvasId
        && typeof message.metadata.revision === 'number')
      .sort((left, right) => (right.metadata?.revision ?? -1) - (left.metadata?.revision ?? -1))[0];
    const revision = latest?.metadata?.revision;
    if (revision === undefined) return;
    const key = `${canvasMode.conversationId}:${canvasMode.canvasId}`;
    if ((postedCanvasRevisionsRef.current[key] ?? -1) >= revision) return;
    if (!canvasTab) return;
    let origin = '';
    try { origin = new URL(canvasTab.url, window.location.origin).origin; } catch { return; }
    postedCanvasRevisionsRef.current[key] = revision;
    frame.contentWindow.postMessage({
      type: CANVAS_GRAPH_UPDATED_MESSAGE,
      conversationId: canvasMode.conversationId,
      projectId: canvasMode.projectId,
      canvasId: canvasMode.canvasId,
      revision,
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
        onLayoutChange={(layout) => apps.setLayout((current) => ({ ...current, ...layout }))}
        minimized={apps.layout.minimized}
      />
    </>
  );
}
