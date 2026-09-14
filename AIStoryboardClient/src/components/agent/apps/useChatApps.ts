import { useCallback, useEffect, useState } from 'react';
import type { EmbeddedAppDescriptor } from './EmbeddedAppWindow';
import {
  buildChatAppDescriptor,
  type ChatAppKey,
  type ChatAppProject,
} from './chatAppDefinitions';

export interface ChatAppLayout {
  width: number;
  height: number;
  fullscreen: boolean;
}

export function useChatApps(currentProject: ChatAppProject | null) {
  const [tabs, setTabs] = useState<EmbeddedAppDescriptor[]>([]);
  const [activeKey, setActiveKey] = useState<ChatAppKey | null>(null);
  const [layout, setLayout] = useState<ChatAppLayout>({
    width: 420,
    height: 0,
    fullscreen: false,
  });

  const open = useCallback((key: ChatAppKey) => {
    const nextTab = buildChatAppDescriptor(key, currentProject);
    if (!nextTab) return;
    setTabs((currentTabs) => {
      const existingIndex = currentTabs.findIndex((tab) => tab.key === key);
      if (existingIndex < 0) return [...currentTabs, nextTab];
      return currentTabs.map((tab) => tab.key === key ? nextTab : tab);
    });
    setActiveKey(key);
  }, [currentProject]);

  useEffect(() => {
    setTabs((currentTabs) => {
      if (currentTabs.length === 0) return currentTabs;
      let changed = false;
      const nextTabs = currentTabs.map((tab) => {
        const next = buildChatAppDescriptor(tab.key, currentProject);
        if (next && next.url !== tab.url) {
          changed = true;
          return next;
        }
        return tab;
      });
      return changed ? nextTabs : currentTabs;
    });
  }, [currentProject]);

  const closeAll = useCallback(() => {
    setTabs([]);
    setActiveKey(null);
    setLayout((current) => ({ ...current, fullscreen: false }));
  }, []);

  const close = useCallback((key: ChatAppKey) => {
    setTabs((currentTabs) => {
      const closingIndex = currentTabs.findIndex((tab) => tab.key === key);
      if (closingIndex < 0) return currentTabs;
      const nextTabs = currentTabs.filter((tab) => tab.key !== key);
      setActiveKey((currentKey) => {
        if (currentKey !== key) return currentKey;
        return nextTabs[Math.min(closingIndex, nextTabs.length - 1)]?.key ?? null;
      });
      if (nextTabs.length === 0) {
        setLayout((current) => ({ ...current, fullscreen: false }));
      }
      return nextTabs;
    });
  }, []);

  const activeTab = tabs.find((tab) => tab.key === activeKey) ?? null;
  const windowOpen = Boolean(activeTab && !layout.fullscreen);
  const select = useCallback((key: ChatAppKey) => setActiveKey(key), []);

  return {
    tabs,
    activeKey,
    activeTab,
    layout,
    windowOpen,
    hasTabs: tabs.length > 0,
    open,
    close,
    closeAll,
    select,
    setLayout,
  };
}

export type ChatAppsController = ReturnType<typeof useChatApps>;
