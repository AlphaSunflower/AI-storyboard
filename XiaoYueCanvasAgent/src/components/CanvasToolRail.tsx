import { Panel } from '@xyflow/react';
import { Library, Upload } from 'lucide-react';
import {
  listNodeDefinitions, type CanvasNodeKind, type NodeCategory, type NodeDefinition,
} from '../canvas/graphContracts';
import { nodeKindIcon } from '../canvas/nodeKindIcon';

/**
 * 左栏节点顺序：按分类分块排列，块内沿用 NODE_DEFINITIONS 的声明顺序。
 * 不能直接用 listNodeDefinitions() 的顺序——它是对象字面量的声明顺序，
 * 会把「视频生成」排在「图片生成」前面，读起来像乱序。
 */
const CATEGORY_ORDER: readonly NodeCategory[] = ['输入', '分镜', '图像生成', '视频生成', '视频处理', '辅助'];

const categoryRank = (category: NodeCategory) => {
  const index = CATEGORY_ORDER.indexOf(category);
  return index === -1 ? CATEGORY_ORDER.length : index;
};

function orderedDefinitions(): NodeDefinition[] {
  // sort 是稳定的，所以同一分类内部的声明顺序会被保留。
  return [...listNodeDefinitions()].sort((a, b) => categoryRank(a.category) - categoryRank(b.category));
}

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
 */
export function CanvasToolRail({ onAddNode, onUpload, onToggleAssets, assetsActive = false }: CanvasToolRailProps) {
  return (
    <Panel className="canvas-tool-island" position="top-left" aria-label="画布工具栏">
      {orderedDefinitions().map((definition) => {
        const Icon = nodeKindIcon(definition.kind);
        return (
          <button
            key={definition.kind}
            type="button"
            onClick={() => onAddNode(definition.kind)}
            aria-label={definition.title}
            title={definition.title}
          >
            <span className="tool-rail-icon"><Icon size={24} /></span>
            <span className="tool-rail-label">{definition.title}</span>
          </button>
        );
      })}
      <i className="tool-rail-divider" aria-hidden="true" />
      <div className="canvas-tool-group">
        <button type="button" onClick={onUpload} aria-label="上传" title="上传本地素材">
          <span className="tool-rail-icon"><Upload size={24} /></span>
          <span className="tool-rail-label">上传</span>
        </button>
        <button
          type="button"
          className={assetsActive ? 'is-active' : undefined}
          onClick={onToggleAssets}
          aria-label="项目库"
          title="项目库"
        >
          <span className="tool-rail-icon"><Library size={24} /></span>
          <span className="tool-rail-label">项目库</span>
        </button>
      </div>
    </Panel>
  );
}
