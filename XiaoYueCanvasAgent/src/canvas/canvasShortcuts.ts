import {
  listNodeDefinitions,
  type CanvasNodeKind,
  type NodeCategory,
  type NodeDefinition,
} from './graphContracts';

/**
 * 画布快捷键单一真源。
 *
 * <p>节点键位（1–9）按左栏工具岛的实际顺序派生，不手写第二份名单：
 * 以后节点库增删，只要仍在 `listNodeDefinitions()` 里，键位会自动跟着走。
 * 动作键位用单字母（画布没有常驻输入框，输入焦点会被 `isEditableShortcutTarget` 过滤）。
 */

const CATEGORY_ORDER: readonly NodeCategory[] = ['输入', '分镜', '图像生成', '视频生成', '视频处理', '辅助'];

const categoryRank = (category: NodeCategory) => {
  const index = CATEGORY_ORDER.indexOf(category);
  return index === -1 ? CATEGORY_ORDER.length : index;
};

/** 与左栏工具岛完全相同的顺序：按分类分块，块内保留声明顺序。 */
export function orderedCanvasNodeDefinitions(): NodeDefinition[] {
  return [...listNodeDefinitions()].sort((left, right) => categoryRank(left.category) - categoryRank(right.category));
}

export interface CanvasNodeShortcut {
  kind: CanvasNodeKind;
  key: string;
  label: string;
}

export const CANVAS_NODE_SHORTCUTS: readonly CanvasNodeShortcut[] = orderedCanvasNodeDefinitions()
  .slice(0, 9)
  .map((definition, index) => ({
    kind: definition.kind,
    key: String(index + 1),
    label: definition.title,
  }));

const NODE_SHORTCUT_BY_KIND = new Map(CANVAS_NODE_SHORTCUTS.map((shortcut) => [shortcut.kind, shortcut]));

export function canvasNodeShortcutFor(kind: string): CanvasNodeShortcut | undefined {
  return NODE_SHORTCUT_BY_KIND.get(kind as CanvasNodeKind);
}

export type CanvasShortcutActionId =
  | 'upload'
  | 'library'
  | 'map'
  | 'sidebar'
  | 'layout'
  | 'edges'
  | 'nodes'
  | 'records'
  | 'queue'
  | 'help';

export interface CanvasActionShortcut {
  id: CanvasShortcutActionId;
  key: string;
  label: string;
  description: string;
}

export const CANVAS_ACTION_SHORTCUTS: readonly CanvasActionShortcut[] = [
  { id: 'upload', key: 'U', label: '上传', description: '打开本地文件选择器，导入图片 / 视频' },
  { id: 'library', key: 'L', label: '项目库', description: '打开 / 关闭项目库抽屉' },
  { id: 'map', key: 'M', label: '打开地图', description: '显示 / 隐藏左下角导航小地图' },
  { id: 'sidebar', key: 'B', label: '隐藏侧边栏', description: '隐藏 / 显示右侧 Moon 智能体与节点属性面板' },
  { id: 'layout', key: 'O', label: '整理画布', description: '按连线关系自动整理选中节点或整张画布' },
  { id: 'edges', key: 'E', label: '隐藏连线', description: '隐藏 / 显示连线；点节点可单独看它的连线' },
  { id: 'nodes', key: 'H', label: '隐藏节点', description: '收起 / 展开全部节点（紧凑与完整视图切换）' },
  { id: 'records', key: 'G', label: '展示生成记录', description: '打开 / 关闭生成记录面板' },
  { id: 'queue', key: 'Q', label: '展示任务队列', description: '打开 / 关闭正在执行的任务队列' },
  { id: 'help', key: '?', label: '快捷键帮助', description: '打开 / 关闭这张快捷键表' },
];

export type CanvasShortcutMatch =
  | { type: 'node'; kind: CanvasNodeKind; key: string; label: string }
  | { type: 'action'; id: CanvasShortcutActionId; key: string; label: string };

export interface CanvasShortcutKeyEvent {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  repeat?: boolean;
  isComposing?: boolean;
}

const EDITABLE_SELECTOR = [
  'input',
  'textarea',
  'select',
  '[contenteditable="true"]',
  '[contenteditable=""]',
  '[role="textbox"]',
].join(', ');

/** 输入框、文字节点预览面、富文本编辑器里的按键不参与画布快捷键。 */
export function isEditableShortcutTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element || typeof element.closest !== 'function') return false;
  return Boolean(element.closest(EDITABLE_SELECTOR));
}

/**
 * 命中返回节点或动作；未命中返回 null。
 *
 * <p>节点用无修饰数字键（1–9），动作用无修饰单字母；`?` 需要 Shift，单独放行。
 * Ctrl/Cmd/Alt 组合一律不抢——留给浏览器和输入卡自己的快捷键。
 */
export function matchCanvasShortcut(event: CanvasShortcutKeyEvent): CanvasShortcutMatch | null {
  if (event.repeat || event.isComposing) return null;
  if (event.ctrlKey || event.metaKey || event.altKey) return null;

  if (event.key === '?') {
    const help = CANVAS_ACTION_SHORTCUTS.find((shortcut) => shortcut.id === 'help')!;
    return { type: 'action', id: 'help', key: help.key, label: help.label };
  }

  if (event.shiftKey) return null;

  const node = CANVAS_NODE_SHORTCUTS.find((shortcut) => shortcut.key === event.key);
  if (node) {
    return { type: 'node', kind: node.kind, key: node.key, label: node.label };
  }

  const action = CANVAS_ACTION_SHORTCUTS.find(
    (shortcut) => shortcut.id !== 'help' && shortcut.key.toLowerCase() === event.key.toLowerCase(),
  );
  if (action) {
    return { type: 'action', id: action.id, key: action.key, label: action.label };
  }
  return null;
}

/** 帮助面板用：节点与动作分组。 */
export function groupCanvasShortcuts(): Array<{
  group: string;
  items: Array<{ key: string; label: string; description: string }>;
}> {
  return [
    {
      group: '添加节点',
      items: CANVAS_NODE_SHORTCUTS.map((shortcut) => ({
        key: shortcut.key,
        label: shortcut.label,
        description: '在视口中心添加该节点',
      })),
    },
    {
      group: '画布与视图',
      items: CANVAS_ACTION_SHORTCUTS.map((shortcut) => ({
        key: shortcut.key,
        label: shortcut.label,
        description: shortcut.description,
      })),
    },
    {
      group: '分组',
      items: [
        { key: 'Ctrl G', label: '创建分组', description: '把当前选中的多个节点合并成一个可整组拖动的分组框' },
        { key: 'Ctrl ⇧ G', label: '解散分组', description: '解散选中节点所在的分组，节点保留在画布上' },
      ],
    },
  ];
}
