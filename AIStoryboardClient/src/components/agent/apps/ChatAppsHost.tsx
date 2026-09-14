import { useEffect } from 'react';
import { EmbeddedAppWindow } from './EmbeddedAppWindow';
import { useAgentStore } from '../../../stores/agentStore';
import {
  buildSceneReviewPrompt,
  isStoryboardReferenceMessage,
} from '../../../lib/embeddedAppMessages';
import type { ChatAppsController } from './useChatApps';

export function ChatAppsHost({
  apps,
  currentProjectId,
  sidebarCollapsed,
  onCollapseSidebar,
  expandedWidthBonus,
}: {
  apps: ChatAppsController;
  currentProjectId?: string;
  sidebarCollapsed: boolean;
  onCollapseSidebar: () => void;
  expandedWidthBonus: number;
}) {
  useEffect(() => {
    if (!currentProjectId) return;
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin || !isStoryboardReferenceMessage(event.data)) return;
      const sourceMatches = Array.from(document.querySelectorAll('iframe'))
        .some((frame) => frame.contentWindow === event.source);
      if (!sourceMatches || event.data.projectId !== currentProjectId) return;
      void useAgentStore.getState().sendMessage(
        buildSceneReviewPrompt(event.data.scenes),
        { routingHint: 'intent-scene-review' },
      );
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [currentProjectId]);

  if (!apps.hasTabs || !apps.activeKey) return null;

  return (
    <EmbeddedAppWindow
      tabs={apps.tabs}
      activeKey={apps.activeKey}
      onSelectTab={apps.select}
      onCloseTab={apps.close}
      onCloseWindow={apps.closeAll}
      sidebarCollapsed={sidebarCollapsed}
      onCollapseSidebar={onCollapseSidebar}
      expandedWidthBonus={expandedWidthBonus}
      onLayoutChange={apps.setLayout}
    />
  );
}
