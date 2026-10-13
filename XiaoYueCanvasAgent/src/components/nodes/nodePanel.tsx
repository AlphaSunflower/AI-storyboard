import type { ReactNode } from 'react';
import { NodeToolbar, Position } from '@xyflow/react';

/**
 * 画布节点统一外壳（除文字节点外全部节点共用）：
 * - NodeSurface：节点框内的预览面。空态只放图标 + 一句说明，不放任何操作控件。
 * - NodeFloatPanel：脱离节点框、挂在节点正下方的编辑面板。所有操作（上传 / 生成 / 合并 / 改动）
 *   都放这里，与生成节点的 composer 同一套机制（NodeToolbar 挂到画布渲染层）。
 *
 * 用 NodeToolbar 而不是节点内的 absolute 定位：节点是一层层叠起来的 DOM，
 * 落在节点框外的面板会被下方节点压住；NodeToolbar 渲染在画布渲染层，
 * 天然浮在所有节点之上，且不随画布缩放（屏幕尺寸恒定）。
 */

export type NodeSurfaceState = 'empty' | 'ready' | 'busy';

export function NodeSurface({
  state = 'empty',
  className,
  children,
}: {
  state?: NodeSurfaceState;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={`node-surface${className ? ` ${className}` : ''}`} data-state={state}>
      {children}
    </div>
  );
}

/** 预览面空态：一个图标 + 一句说明（可再加一行更小的提示）。 */
export function NodeSurfaceEmpty({
  icon,
  label,
  hint,
}: {
  icon: ReactNode;
  label: string;
  hint?: string;
}) {
  return (
    <div className="node-surface__empty">
      {icon}
      <strong>{label}</strong>
      {hint ? <span>{hint}</span> : null}
    </div>
  );
}

export function NodeFloatPanel({
  nodeId,
  isVisible,
  className,
  children,
}: {
  nodeId: string;
  /** 默认跟随选中态（与生成节点的 composer 一致）；需要常驻时可传 true。 */
  isVisible?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <NodeToolbar
      nodeId={nodeId}
      isVisible={isVisible}
      position={Position.Bottom}
      align="center"
      offset={14}
      className="node-float-toolbar"
      onPointerDown={(event) => event.stopPropagation()}
    >
      <div className={`node-float-panel nodrag nowheel${className ? ` ${className}` : ''}`}>
        {children}
      </div>
    </NodeToolbar>
  );
}

