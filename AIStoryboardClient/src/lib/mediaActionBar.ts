/**
 * 图片/视频底部操作条的排布规则。
 * 尚未接通的能力集中在这张表里：点击只给一句「未实现该功能」，
 * 后续接通某个能力时，把它从表里删掉即可。
 */
export const UNIMPLEMENTED_MEDIA_ACTIONS = new Set([
  '智能改图',
  '智能超清',
  '局部重绘',
  '消除笔',
  '扩图',
  '多角度',
]);

export function isUnimplementedMediaAction(label: string): boolean {
  return UNIMPLEMENTED_MEDIA_ACTIONS.has(label);
}

/** 单个图标按钮（30px）+ 间距（2px） */
const ACTION_SLOT = 32;
/** 操作条自身左右内边距合计 */
const BAR_PADDING = 6;
/** 操作条相对画框的留白，与 .chat-media-actions__bar 的 max-width: calc(100% - 16px) 对齐 */
const BAR_INSET = 16;
/** 「⋯」按钮自己也要占一个位 */
const MORE_SLOT = 32;
/** 宁可少露一个，也不要挤成一团 */
const SAFETY_GAP = 6;

export interface MediaActionBarPlan {
  /** 底部条里直接露出的操作数 */
  visible: number;
  /** 挪进「⋯」的操作数；为 0 时连「⋯」按钮都不用渲染 */
  overflow: number;
}

/**
 * 底部条排布：能放下全部功能就全部展示（连「⋯」都不出现），
 * 放不下才按顺序从尾部挪进「⋯」，并给「⋯」自己留一个位。
 * 宽度未知（未测量 / 测试环境）时按全部可见处理。
 */
export function planActionBar(frameWidth: number, total: number): MediaActionBarPlan {
  const count = Math.max(0, total);
  if (!Number.isFinite(frameWidth) || frameWidth <= 0) return { visible: count, overflow: 0 };
  const available = frameWidth - BAR_INSET - BAR_PADDING;
  if (available >= count * ACTION_SLOT) return { visible: count, overflow: 0 };
  const roomForIcons = available - MORE_SLOT - SAFETY_GAP;
  const visible = Math.max(1, Math.min(count - 1, Math.floor(roomForIcons / ACTION_SLOT)));
  return { visible, overflow: count - visible };
}
