import type { ReactNode } from 'react';
import { NodeToolbar, Position, useStore } from '@xyflow/react';
import { usePresence } from '../../hooks/usePresence';

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
  // 复刻 React Flow 的原生显示规则（单选本节点才显示），但把「显示」拆成
  // mounted / present 两步，取消选中时先播退场再卸载，而不是直接消失。
  const selected = useStore((state) => state.nodes?.find((node) => node.id === nodeId)?.selected ?? false);
  const singleSelection = useStore((state) => (state.nodes ?? []).filter((node) => node.selected).length === 1);
  // 进场：keyframe 动画（见 CSS .motion-presence）；退场：立即卸载。
  // 节点面板是画布上最频繁切换的浮层，多留的挂载窗口会把画布容器撑出额外的可滚动区域，
  // 让浏览器/自动化的一次 scrollIntoView 静默移动整张画布（React Flow 并不知情）。
  const presence = usePresence(isVisible ?? (selected && singleSelection), { duration: 130 });
  return (
    <NodeToolbar
      nodeId={nodeId}
      isVisible={presence.mounted}
      position={Position.Bottom}
      align="center"
      offset={14}
      // 外壳只带「让开点击」的退场态（自带 pointer-events:auto，不退场的话关闭后还能挡住画布点击）；
      // 位移/缩放交给里面那层，避免外壳和面板各动一次互相抵消。
      className="node-float-toolbar motion-passthrough"
      data-present={presence.present}
      onPointerDown={(event) => event.stopPropagation()}
    >
      <div
        className={`node-float-panel nodrag nowheel motion-presence${className ? ` ${className}` : ''}`}
        data-present={presence.present}
      >
        {children}
      </div>
    </NodeToolbar>
  );
}

