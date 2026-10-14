import { useRef, type PointerEvent as ReactPointerEvent } from 'react';
import { Handle, Position, useReactFlow } from '@xyflow/react';
import { Plus } from 'lucide-react';
import type { NodeDefinition } from '../../canvas/graphContracts';

/** 连接点按下后位移不超过这个像素数，就当成「点击建节点」而不是「拖拽连线」。 */
const PORT_CLICK_DRIFT_PX = 5;
/** 节点定义缺失时的兜底端口，行为与改造前一致。 */
const FALLBACK_INPUTS = [{ id: 'left' }];
const FALLBACK_OUTPUTS = [{ id: 'right' }];

type NodePortHandlesProps = {
  nodeId: string;
  nodeKind: string;
  definition: NodeDefinition | undefined;
};

/**
 * 节点两侧的连接点。MediaNode / CompactNodeView / VideoNode 三个渲染器共用，
 * 避免各自手写 <Handle> 之后样式与行为再次漂移（历史上就漂过一次）。
 *
 * 每侧只画一个可见的即梦式「+」圆钮：同侧其余端口的 Handle 仍留在 DOM 里
 * （连线靠它锚定，删了边会断），但不可见也不可连。具体落到哪个端口由 store
 * 按媒体自动匹配（resolveOutputPort / resolveInputPort），所以不用对准具体哪个点。
 */
export function NodePortHandles({ nodeId, nodeKind, definition }: NodePortHandlesProps) {
  const { screenToFlowPosition } = useReactFlow();
  const pointerStart = useRef<{ x: number; y: number } | null>(null);

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    pointerStart.current = { x: event.clientX, y: event.clientY };
  };

  // 连接点兼作「点击建节点」按钮：按下后几乎没动就算点击，拖拽连线仍交给 React Flow。
  // 这里用 onPointerDown / onPointerUp 而不是 onClick —— React Flow 在 connectOnClick 为 false 时
  // 会把 onClick 丢弃，而 onMouseDown / onTouchStart 已被它自己占用；这两个是叠加而非覆盖。
  const onPointerUp = (event: ReactPointerEvent<HTMLDivElement>, handleType: 'source' | 'target', handleId: string) => {
    const start = pointerStart.current;
    pointerStart.current = null;
    if (!start || Math.hypot(event.clientX - start.x, event.clientY - start.y) > PORT_CLICK_DRIFT_PX) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const clientX = rect.left + rect.width / 2;
    const clientY = rect.top + rect.height / 2;
    const point = screenToFlowPosition({ x: clientX, y: clientY });
    window.dispatchEvent(new CustomEvent('connection-end', {
      detail: {
        origin: 'click',
        clientX,
        clientY,
        flowX: point.x,
        flowY: point.y,
        sourceNodeId: nodeId,
        sourceNodeType: nodeKind,
        handleType,
        handleId,
      },
    }));
  };

  const renderSide = (ports: { id: string }[], type: 'source' | 'target', position: Position) =>
    ports.map((port, index) => {
      const primary = index === 0;
      return (
        <Handle
          key={port.id}
          className={primary ? 'node-handle' : 'node-handle node-handle--passive'}
          id={port.id}
          type={type}
          position={position}
          isConnectable={primary}
          onPointerDown={primary ? onPointerDown : undefined}
          onPointerUp={primary ? (event) => onPointerUp(event, type, port.id) : undefined}
        >
          {primary && (
            <span className="node-handle__plus" aria-hidden="true">
              <Plus size={20} strokeWidth={1.75} />
            </span>
          )}
        </Handle>
      );
    });

  return (
    <>
      {renderSide(definition ? definition.inputs : FALLBACK_INPUTS, 'target', Position.Left)}
      {renderSide(definition ? definition.outputs : FALLBACK_OUTPUTS, 'source', Position.Right)}
    </>
  );
}
