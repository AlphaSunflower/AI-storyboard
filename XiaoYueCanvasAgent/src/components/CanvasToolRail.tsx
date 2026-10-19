import { Panel } from '@xyflow/react';
import { Library, Upload } from 'lucide-react';
import type { CanvasNodeKind } from '../canvas/graphContracts';
import { canvasNodeShortcutFor, orderedCanvasNodeDefinitions } from '../canvas/canvasShortcuts';
import { nodeKindIcon } from '../canvas/nodeKindIcon';

export interface CanvasToolRailProps {
  /** 点节点按钮。落点由父级决定（视口中心），本组件不碰 React Flow 坐标。 */
  onAddNode: (kind: CanvasNodeKind) => void;
  /** 打开本地文件选择器 */
  onUpload: () => void;
  onToggleAssets: () => void;
  assetsActive?: boolean;
}

/**
 * 画布左栏：节点库里的节点全部平铺成图标（没有「+」按钮、没有抽屉），
 * 末尾一条分隔线接「上传」和「项目库」。
 *
 * 名称常驻 DOM，展开/收起只切显隐（CSS 的 :hover / :focus-within），
 * 所以键盘用户和触屏用户都能拿到名称，不需要额外的 tooltip 挂载逻辑。
 * 键位来自 `canvasShortcuts`，与键盘监听共享同一份顺序。
 */
export function CanvasToolRail({ onAddNode, onUpload, onToggleAssets, assetsActive = false }: CanvasToolRailProps) {
  return (
    <Panel className="canvas-tool-island" position="top-left" aria-label="画布工具栏">
      {orderedCanvasNodeDefinitions().map((definition) => {
        const Icon = nodeKindIcon(definition.kind);
        const shortcut = canvasNodeShortcutFor(definition.kind);
        return (
          <button
            key={definition.kind}
            type="button"
            onClick={() => onAddNode(definition.kind)}
            aria-label={definition.title}
            title={shortcut ? `${definition.title}（快捷键 ${shortcut.key}）` : definition.title}
          >
            <span className="tool-rail-icon"><Icon size={24} /></span>
            <span className="tool-rail-label">{definition.title}</span>
            {shortcut && <kbd className="tool-rail-key" aria-hidden="true">{shortcut.key}</kbd>}
          </button>
        );
      })}
      <i className="tool-rail-divider" aria-hidden="true" />
      <div className="canvas-tool-group">
        <button type="button" onClick={onUpload} aria-label="上传" title="上传本地素材（快捷键 U）">
          <span className="tool-rail-icon"><Upload size={24} /></span>
          <span className="tool-rail-label">上传</span>
          <kbd className="tool-rail-key" aria-hidden="true">U</kbd>
        </button>
        <button
          type="button"
          className={assetsActive ? 'is-active' : undefined}
          onClick={onToggleAssets}
          aria-label="项目库"
          title="项目库（快捷键 L）"
        >
          <span className="tool-rail-icon"><Library size={24} /></span>
          <span className="tool-rail-label">项目库</span>
          <kbd className="tool-rail-key" aria-hidden="true">L</kbd>
        </button>
      </div>
    </Panel>
  );
}
