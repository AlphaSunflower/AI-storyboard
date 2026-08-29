import { useEffect, useRef, useState } from 'react';
import { useCanvasStore, canConnectNodes } from '../store/canvasStore';
import { NODE_DEFS, listMenuNodes, getNodeDefByType } from '../domain/nodeRegistry';

/**
 * 「连接即创建」菜单：监听 CanvasWorkspace 拖线松手未落到节点时 dispatch 的
 * 'connection-end' 事件，在鼠标位置弹出「选择节点类型」菜单，选中后创建节点并自动连线。
 * - 从源端口往外拖（source handle）：创建新节点连到 source 的右边（createConnectedNode）
 * - 从目标端口往里拖（target handle）：创建新节点排到 target 左边作为前置（createNodeBefore）
 *
 * 连通性口径与 store 统一（canConnectNodes）：每个源节点声明「能连到哪些目标」的白名单，
 * 非空=仅白名单内可连，空数组=该源不允许连出任何目标。菜单只列出允许的目标/上游类型。
 */
type MenuState = {
  visible: boolean;
  x: number;
  y: number;
  flowX: number;
  flowY: number;
  sourceNodeId: string;
  sourceNodeType: string;
  handleType: 'source' | 'target';
};

export function ConnectionNodeMenu() {
  const [menu, setMenu] = useState<MenuState | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const createConnectedNode = useCanvasStore((s) => s.createConnectedNode);
  const createNodeBefore = useCanvasStore((s) => s.createNodeBefore);

  useEffect(() => {
    // 刚打开菜单时，document 的 click 捕获也会先触发并立刻关闭它；用 justOpened 跳过这一次。
    let justOpened = false;
    const onConnectEnd = (e: CustomEvent) => {
      const { didConnect, clientX, clientY, flowX, flowY, sourceNodeId, sourceNodeType, handleType } = e.detail;
      if (didConnect) return;
      const sourceDef = getNodeDefByType(sourceNodeType);
      if (!sourceDef) return;
      setMenu({ visible: true, x: clientX, y: clientY, flowX: flowX ?? clientX, flowY: flowY ?? clientY, sourceNodeId, sourceNodeType, handleType: handleType ?? 'source' });
    };
    const onClick = (e: MouseEvent) => {
      if (justOpened) { justOpened = false; return; }
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(null);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(null); };
    window.addEventListener('connection-end' as never, onConnectEnd);
    document.addEventListener('click', onClick, true);
    document.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('connection-end' as never, onConnectEnd);
      document.removeEventListener('click', onClick, true);
      document.removeEventListener('keydown', onKey);
    };
  }, []);

  if (!menu) return null;
  if (menu.handleType === 'target') {
    // 目标侧（往 target 拖）：列出「能连入此目标」的节点类型，作为上游可选。
    const targetKind = menu.sourceNodeType as any;
    const options = listMenuNodes().filter((d) => canConnectNodes(d.type as any, targetKind));
    return (
      <div ref={menuRef} className="connection-node-menu" style={{ left: menu.x, top: menu.y }} onClick={(e) => e.stopPropagation()}>
        <header>选择上游节点类型</header>
        {options.length === 0 && <p className="connection-node-menu__empty">当前目标节点不接受任何上游连接</p>}
        {options.map((def) => (
          <button key={def.type} onClick={() => { createNodeBefore(menu.sourceNodeId, def.type as any, { x: menu.flowX, y: menu.flowY }); setMenu(null); }}>
            <span>{def.menuLabel}</span>
          </button>
        ))}
      </div>
    );
  }
  // 源侧（从 source 拖）：列出「源节点允许连到」的节点类型，作为下游可选。
  const sourceType = menu.sourceNodeType as any;
  const sourceDef = (NODE_DEFS as Record<string, any>)[sourceType];
  const options = (sourceDef?.connectivity?.acceptTypes ?? []).map((t: string) => NODE_DEFS[t]).filter(Boolean) as Array<{ type: string; menuLabel: string }>;
  return (
    <div ref={menuRef} className="connection-node-menu" style={{ left: menu.x, top: menu.y }} onClick={(e) => e.stopPropagation()}>
      <header>选择下游节点类型</header>
      {options.length === 0 && <p className="connection-node-menu__empty">当前节点不能连接任何下游节点</p>}
      {options.map((def) => (
        <button key={def.type} onClick={() => { createConnectedNode(menu.sourceNodeId, def.type as any, { x: menu.flowX, y: menu.flowY }); setMenu(null); }}>
          <span>{def.menuLabel}</span>
        </button>
      ))}
    </div>
  );
}
