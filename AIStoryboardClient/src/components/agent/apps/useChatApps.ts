import { useCallback, useEffect, useState } from 'react';
import type { CanvasModeOptions, EmbeddedAppDescriptor } from './EmbeddedAppWindow';
import {
  buildChatAppDescriptor,
  type ChatAppKey,
  type ChatAppOpenOptions,
  type ChatAppProject,
} from './chatAppDefinitions';

export interface ChatAppLayout {
  width: number;
  height: number;
  fullscreen: boolean;
  minimized: boolean;
}

function sameCanvasMode(a?: CanvasModeOptions, b?: CanvasModeOptions): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return a.conversationId === b.conversationId
    && a.projectId === b.projectId
    && a.canvasId === b.canvasId
    && a.parentOrigin === b.parentOrigin;
}

/**
 * 同一个 key 的 tab 内容没变时保持原数组引用。
 * open() 会被多个「确保 tab 已打开」的 effect 调用，若每次都产出新对象，
 * 依赖 chatAppTabs 的 effect 会被反复触发，形成 setState 死循环。
 */
function isSameDescriptor(current: EmbeddedAppDescriptor, next: EmbeddedAppDescriptor): boolean {
  return current.key === next.key
    && current.url === next.url
    && current.projectId === next.projectId
    && current.title === next.title
    && sameCanvasMode(current.canvasMode, next.canvasMode)
    && JSON.stringify(current.documentAttachment ?? null)
      === JSON.stringify(next.documentAttachment ?? null);
}

export function useChatApps(currentProject: ChatAppProject | null) {
  const [tabs, setTabs] = useState<EmbeddedAppDescriptor[]>([]);
  const [activeKey, setActiveKey] = useState<ChatAppKey | null>(null);
  const [layout, setLayout] = useState<ChatAppLayout>({
    width: 420,
    height: 0,
    fullscreen: false,
    minimized: false,
  });

  const open = useCallback((key: ChatAppKey, options: ChatAppOpenOptions = {}) => {
    const nextTab = buildChatAppDescriptor(key, currentProject, options);
    if (!nextTab) return;
    setTabs((currentTabs) => {
      const existingIndex = currentTabs.findIndex((tab) => tab.key === key);
      if (existingIndex < 0) return [...currentTabs, nextTab];
      if (isSameDescriptor(currentTabs[existingIndex], nextTab)) return currentTabs;
      return currentTabs.map((tab) => tab.key === key ? nextTab : tab);
    });
    setActiveKey(key);
    setLayout((current) => (current.minimized ? { ...current, minimized: false } : current));
  }, [currentProject]);

  useEffect(() => {
    setTabs((currentTabs) => {
      if (currentTabs.length === 0) return currentTabs;
      let changed = false;
      const nextTabs = currentTabs.map((tab) => {
        const next = buildChatAppDescriptor(tab.key, currentProject, { canvasMode: tab.canvasMode });
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
    setLayout((current) => ({ ...current, fullscreen: false, minimized: false }));
  }, []);

  const releaseCanvasMode = useCallback(() => {
    setTabs((currentTabs) => currentTabs.map((tab) => {
      if (tab.key !== 'canvas' || !tab.canvasMode) return tab;
      const { canvasMode: _canvasMode, ...ordinaryTab } = tab;
      try {
        const url = new URL(tab.url);
        url.searchParams.delete('canvasMode');
        url.searchParams.delete('conversationId');
        url.searchParams.delete('parentOrigin');
        const projectId = tab.canvasMode.projectId;
        const canvasId = tab.canvasMode.canvasId;
        url.hash = canvasId
          ? `#project=${encodeURIComponent(projectId)}&canvas=${encodeURIComponent(canvasId)}`
          : `#project=${encodeURIComponent(projectId)}`;
        return { ...ordinaryTab, url: url.toString() };
      } catch {
        return ordinaryTab;
      }
    }));
  }, []);

  const bindCanvasMode = useCallback((canvasId: string) => {
    setTabs((currentTabs) => currentTabs.map((tab) => {
      if (tab.key !== 'canvas' || !tab.canvasMode || !canvasId) return tab;
      return { ...tab, canvasMode: { ...tab.canvasMode, canvasId } };
    }));
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
        setLayout((current) => ({ ...current, fullscreen: false, minimized: false }));
      }
      return nextTabs;
    });
  }, []);

  const activeTab = tabs.find((tab) => tab.key === activeKey) ?? null;
  const windowOpen = Boolean(activeTab && !layout.fullscreen && !layout.minimized);
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
    releaseCanvasMode,
    bindCanvasMode,
    select,
    setLayout,
  };
}

export type ChatAppsController = ReturnType<typeof useChatApps>;
