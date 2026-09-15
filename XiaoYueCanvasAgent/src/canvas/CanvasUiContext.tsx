import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react';
import { useCanvasStore } from '../store/canvasStore';

interface CanvasUiContextValue {
  openInspector: (nodeId?: string) => void;
}

const CanvasUiContext = createContext<CanvasUiContextValue | null>(null);

export function CanvasUiProvider({
  openInspector,
  children,
}: {
  openInspector: () => void;
  children: ReactNode;
}) {
  const openInspectorForNode = useCallback((nodeId?: string) => {
    if (nodeId) useCanvasStore.getState().selectNode(nodeId);
    openInspector();
  }, [openInspector]);
  const value = useMemo(() => ({ openInspector: openInspectorForNode }), [openInspectorForNode]);

  return (
    <CanvasUiContext.Provider value={value}>
      {children}
    </CanvasUiContext.Provider>
  );
}

export function useCanvasUi() {
  const value = useContext(CanvasUiContext);
  if (!value) throw new Error('useCanvasUi must be used inside CanvasUiProvider');
  return value;
}
