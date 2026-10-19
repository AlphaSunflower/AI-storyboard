/**
 * Moon 智能体（/chat）的键盘快捷键单一真源。
 *
 * <p>页面级快捷键（搜索、生成中心、编辑器、画布、项目库）由 {@code ChatPage} 消费；
 * 输入卡级快捷键（录音、状态、压缩/清空上下文、侧边对话）由 {@code ChatComposer} 消费。
 * 两边都从这份表匹配，避免出现两套键位或帮助面板与实现不一致。
 */

export type MoonShortcutId =
  | 'search'
  | 'record'
  | 'status'
  | 'compact'
  | 'clear'
  | 'side-chat'
  | 'generation-center'
  | 'editor'
  | 'canvas'
  | 'library'
  | 'help';

export type MoonShortcutGroup = '对话与导航' | '输入与上下文';

export interface MoonShortcut {
  id: MoonShortcutId;
  /** 展示用键位；匹配时同时接受 Ctrl 与 macOS 的 Cmd。 */
  keys: string;
  label: string;
  description: string;
  group: MoonShortcutGroup;
}

export const MOON_SHORTCUTS: readonly MoonShortcut[] = [
  {
    id: 'search',
    keys: 'Ctrl/Cmd + F',
    label: '搜索对话',
    description: '按标题和全文搜索当前项目的会话',
    group: '对话与导航',
  },
  {
    id: 'record',
    keys: 'Ctrl/Cmd + Shift + 1',
    label: '快捷录音',
    description: '开始或停止语音输入，识别结果落进输入框',
    group: '输入与上下文',
  },
  {
    id: 'status',
    keys: 'Ctrl/Cmd + Shift + 2',
    label: '展示状态列表',
    description: '打开会话状态卡：上下文占用、运行统计与额度',
    group: '输入与上下文',
  },
  {
    id: 'compact',
    keys: 'Ctrl/Cmd + Shift + 3',
    label: '压缩上下文',
    description: '压缩当前对话的上下文，保留最近若干轮',
    group: '输入与上下文',
  },
  {
    id: 'clear',
    keys: 'Ctrl/Cmd + Shift + 4',
    label: '清空上下文',
    description: '真删除当前对话的全部上下文，等同新对话（产出素材保留）',
    group: '输入与上下文',
  },
  {
    id: 'side-chat',
    keys: 'Ctrl/Cmd + Shift + 5',
    label: '打开侧边临时对话',
    description: '基于当前对话开启一个临时分支，不打断主线',
    group: '输入与上下文',
  },
  {
    id: 'generation-center',
    keys: 'Ctrl/Cmd + Shift + 6',
    label: '打开生成中心',
    description: '查看正在生成与已完成的图片 / 视频任务',
    group: '对话与导航',
  },
  {
    id: 'editor',
    keys: 'Ctrl/Cmd + Shift + 7',
    label: '打开编辑器',
    description: '在侧边小窗打开当前项目的分镜编辑器',
    group: '对话与导航',
  },
  {
    id: 'canvas',
    keys: 'Ctrl/Cmd + Shift + 8',
    label: '打开画布',
    description: '在新标签页打开当前项目的画布网站',
    group: '对话与导航',
  },
  {
    id: 'library',
    keys: 'Ctrl/Cmd + Shift + 9',
    label: '打开项目库',
    description: '在侧边小窗打开当前项目的项目库',
    group: '对话与导航',
  },
  {
    id: 'help',
    keys: 'Ctrl/Cmd + /',
    label: '快捷键帮助',
    description: '打开或关闭这张快捷键表',
    group: '对话与导航',
  },
];

/** 页面级快捷键：由 ChatPage 注册的 window 监听器消费。 */
export const MOON_PAGE_SHORTCUT_IDS: ReadonlySet<MoonShortcutId> = new Set<MoonShortcutId>([
  'search',
  'generation-center',
  'editor',
  'canvas',
  'library',
  'help',
]);

/** 输入卡级快捷键：由可见的 ChatComposer 消费，隐藏分支不会响应。 */
export const MOON_COMPOSER_SHORTCUT_IDS: ReadonlySet<MoonShortcutId> = new Set<MoonShortcutId>([
  'record',
  'status',
  'compact',
  'clear',
  'side-chat',
]);

const DIGIT_SHORTCUTS: ReadonlyArray<{ id: MoonShortcutId; digit: number }> = [
  { id: 'record', digit: 1 },
  { id: 'status', digit: 2 },
  { id: 'compact', digit: 3 },
  { id: 'clear', digit: 4 },
  { id: 'side-chat', digit: 5 },
  { id: 'generation-center', digit: 6 },
  { id: 'editor', digit: 7 },
  { id: 'canvas', digit: 8 },
  { id: 'library', digit: 9 },
];

export interface ShortcutKeyEvent {
  key: string;
  code?: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  repeat?: boolean;
  isComposing?: boolean;
}

/** 主键盘区数字键（同时兼容部分布局下 event.key 不是数字的情况）。 */
function isDigit(event: ShortcutKeyEvent, digit: number): boolean {
  return event.code === `Digit${digit}` || event.key === String(digit);
}

/**
 * 命中返回快捷键 id，未命中返回 null。
 *
 * <p>不判断焦点：Ctrl/Cmd 组合在输入框里也应生效（与浏览器查找、编辑器习惯一致），
 * 纯数字/字母键位只在画布侧使用，那里会单独过滤输入焦点。
 */
export function matchMoonShortcut(event: ShortcutKeyEvent): MoonShortcutId | null {
  if (event.repeat || event.isComposing) return null;
  if (event.altKey) return null;

  const command = event.ctrlKey || event.metaKey;
  if (!command) return null;

  // Ctrl/Cmd + F：搜索。Shift+F 不占用，避免和浏览器「查找上一个」混淆。
  if (!event.shiftKey && (event.code === 'KeyF' || event.key.toLowerCase() === 'f')) {
    return 'search';
  }

  // Ctrl/Cmd + /（或 ?）：快捷键帮助；允许 Shift，因为 ? 本身就需要 Shift。
  if (event.code === 'Slash' || event.key === '/' || event.key === '?') {
    return 'help';
  }

  if (!event.shiftKey) return null;

  for (const item of DIGIT_SHORTCUTS) {
    if (isDigit(event, item.digit)) return item.id;
  }
  return null;
}

/** 帮助面板分组顺序稳定：先对话与导航，再输入与上下文。 */
export function groupMoonShortcuts(): Array<{ group: MoonShortcutGroup; items: MoonShortcut[] }> {
  const groups: MoonShortcutGroup[] = ['对话与导航', '输入与上下文'];
  return groups.map((group) => ({
    group,
    items: MOON_SHORTCUTS.filter((item) => item.group === group),
  }));
}
