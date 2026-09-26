import { useEffect } from 'react';
import { EmbeddedAppWindow } from './EmbeddedAppWindow';
import { useAgentStore } from '../../../stores/agentStore';
import {
  buildSceneInspectPrompt,
  isCanvasModeReadyMessage,
  isCanvasModeStatusMessage,
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
      const canvasBase = (import.meta.env.VITE_CANVAS_URL as string | undefined)?.trim().replace(/\/$/, '')
        || 'http://localhost:3000';
      let canvasOrigin = '';
      try { canvasOrigin = new URL(canvasBase).origin; } catch { /* invalid config falls through */ }
      if (!canvasOrigin || event.origin !== canvasOrigin) return;
      if (isCanvasModeStatusMessage(event.data)) onCanvasModeStatus?.(event.data);
      if (isCanvasModeReadyMessage(event.data)) onCanvasModeReady?.(event.data);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [currentProjectId, onCanvasModeReady, onCanvasModeStatus]);

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

  if (!apps.hasTabs || !apps.activeKey) return null;

  return (
    <EmbeddedAppWindow
      tabs={apps.tabs}
      activeKey={apps.activeKey}
      onSelectTab={apps.select}
      onCloseTab={apps.close}
      onCloseWindow={apps.closeAll}
      docked={docked}
      splitMaxWidth={splitMaxWidth}
      expandedSplitMaxWidth={expandedSplitMaxWidth}
      sidebarCollapsed={sidebarCollapsed}
      sidebarCollapseWidth={sidebarCollapseWidth}
      onCollapseSidebar={onCollapseSidebar}
      onRequestDock={onRequestDock}
      onLayoutChange={apps.setLayout}
    />
  );
}
