import { createContext, useContext, type ReactNode } from 'react';

export type NodeDisplayMode = 'compact' | 'expanded';

const ALWAYS_EXPANDED_NODE_KINDS = new Set(['image', 'video', 'text', 'script']);

export function shouldCompactNodeKind(kind: string | undefined) {
  return !kind || !ALWAYS_EXPANDED_NODE_KINDS.has(kind);
}

interface CanvasNodeDisplayContextValue {
  mode: NodeDisplayMode;
  expandedNodeIds: ReadonlySet<string>;
  minimizeNode: (nodeId: string) => void;
}

const CanvasNodeDisplayContext = createContext<CanvasNodeDisplayContextValue>({
  mode: 'expanded',
  expandedNodeIds: new Set(),
  minimizeNode: () => undefined,
});

export function CanvasNodeDisplayProvider({
  mode,
  expandedNodeIds,
  minimizeNode,
  children,
}: CanvasNodeDisplayContextValue & { children: ReactNode }) {
  return (
    <CanvasNodeDisplayContext.Provider value={{ mode, expandedNodeIds, minimizeNode }}>
      {children}
    </CanvasNodeDisplayContext.Provider>
  );
}

export function useCanvasNodeDisplay() {
  return useContext(CanvasNodeDisplayContext);
}
