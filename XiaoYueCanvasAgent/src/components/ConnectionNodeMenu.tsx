import { useEffect, useRef, useState } from 'react';
import { useCanvasStore, canConnectFromPort, canConnectToPort } from '../store/canvasStore';
import { listNodeDefinitions, getNodeDefinition } from '../canvas/graphContracts';

/**
 * 「连接即创建」菜单：监听 CanvasWorkspace 拖线松手未落到节点时 dispatch 的
 * 'connection-end' 事件，在鼠标位置弹出「选择节点类型」菜单，选中后创建节点并自动连线。
 * - 从源端口往外拖（source handle）：创建新节点连到 source 的右边（createConnectedNode）
 * - 从目标端口往里拖（target handle）：创建新节点排到 target 左边作为前置（createNodeBefore）
 *
 * 连通性口径与 store 统一（canConnectNodes）：每个源节点声明「能连到哪些目标」的白名单，
 * 非空=仅白名单内可连，空数组=该源不允许连出任何目标。菜单只列出允许的目标/上游类型。
 */
/** 点击「+」建节点时，新节点与源节点之间留出的横向间距。 */
const NEW_NODE_GAP = 80;
/** 源节点还没被测量出宽度时的兜底值。 */
const FALLBACK_NODE_WIDTH = 420;

type MenuState = {
  visible: boolean;
  x: number;
  y: number;
  flowX: number;
  flowY: number;
  sourceNodeId: string;
  sourceNodeType: string;
  handleType: 'source' | 'target';
  handleId: string;
  /** click = 点击「+」打开（没有鼠标落点，新节点排到源节点外侧）；drag = 拖拽松手（用松手点）。 */
  origin: 'click' | 'drag';
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
      const { didConnect, clientX, clientY, flowX, flowY, sourceNodeId, sourceNodeType, handleType, handleId, origin } = e.detail;
      if (didConnect) return;
      const sourceDef = getNodeDefinition(sourceNodeType);
      if (!sourceDef) return;
      setMenu({
        visible: true,
        x: clientX,
        y: clientY,
        flowX: flowX ?? clientX,
        flowY: flowY ?? clientY,
        sourceNodeId,
        sourceNodeType,
        handleType: handleType ?? 'source',
        handleId: handleId ?? '',
        origin: origin === 'click' ? 'click' : 'drag',
      });
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

  /**
   * 单侧只有一个可见连接点后，这里按「该侧所有端口的并集」列类型，
   * 具体落到哪个端口由 store（resolveOutputPort / resolveInputPort）在创建时按媒体决定。
   */
  const placeNewNode = (def: { defaultSize: { width: number } }) => {
    // 拖拽松手时用鼠标准心；点击「+」没有落点，排到源节点外侧留出空位。
    if (menu.origin !== 'click') return { x: menu.flowX, y: menu.flowY };
    const anchor = useCanvasStore.getState().nodes.find((n) => n.id === menu.sourceNodeId);
    if (!anchor) return { x: menu.flowX, y: menu.flowY };
    const anchorWidth = anchor.width ?? getNodeDefinition(anchor.data.kind)?.defaultSize.width ?? FALLBACK_NODE_WIDTH;
    return menu.handleType === 'source'
      ? { x: anchor.position.x + anchorWidth + NEW_NODE_GAP, y: anchor.position.y }
      : { x: anchor.position.x - NEW_NODE_GAP - def.defaultSize.width, y: anchor.position.y };
  };

  if (menu.handleType === 'target') {
    // 目标侧：列出「能连入此目标任一侧端口」的节点类型，作为上游可选。
    const targetKind = menu.sourceNodeType as any;
    const ports = getNodeDefinition(targetKind)?.inputs ?? [];
    const options = listNodeDefinitions().filter((d) =>
      ports.some((port) => canConnectToPort(targetKind, port.id, d.kind as any)));
    return (
      <div ref={menuRef} className="connection-node-menu" style={{ left: menu.x, top: menu.y }} onClick={(e) => e.stopPropagation()}>
        <header>选择上游节点类型</header>
        {options.length === 0 && <p className="connection-node-menu__empty">当前目标节点不接受任何上游连接</p>}
        {options.map((def) => (
          <button key={def.kind} onClick={() => { createNodeBefore(menu.sourceNodeId, def.kind as any, placeNewNode(def), undefined); setMenu(null); }}>
            <span>{def.title}</span>
          </button>
        ))}
      </div>
    );
  }
  // 源侧：列出「源节点任一侧输出能连到」的节点类型，作为下游可选。
  const sourceType = menu.sourceNodeType as any;
  const outputPorts = getNodeDefinition(sourceType)?.outputs ?? [];
  const options = listNodeDefinitions().filter((def) =>
    outputPorts.some((port) => canConnectFromPort(sourceType, port.id, def.kind)));
  return (
    <div ref={menuRef} className="connection-node-menu" style={{ left: menu.x, top: menu.y }} onClick={(e) => e.stopPropagation()}>
      <header>选择下游节点类型</header>
      {options.length === 0 && <p className="connection-node-menu__empty">当前节点不能连接任何下游节点</p>}
      {options.map((def) => (
        <button key={def.kind} onClick={() => { createConnectedNode(menu.sourceNodeId, def.kind as any, placeNewNode(def), undefined); setMenu(null); }}>
          <span>{def.title}</span>
        </button>
      ))}
    </div>
  );
}
