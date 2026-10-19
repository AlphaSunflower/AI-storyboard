import { agentChatTokens as DS } from './agentChatTokens';
import type { ToolTone } from './toolCallPresentation';

/**
 * 工具调用时间轴的主题：把 `/chat` 已有的 token 摊平成 CSS 变量，交给
 * `ToolCallTimeline.css` 消费，让样式文件里不再出现第二份品牌色。
 *
 * 这里唯一新增的是状态语义色（完成 / 失败 / 提醒）。之所以不直接用
 * `--color-success` / `--color-warning`：那两个是给「图标和徽标」用的亮色
 * （`#5db872` / `#d4a017`），压在浅底上当 11px 文本只有 2:1 左右，读不了。
 * 所以状态胶囊的**文字**用下面这组压暗过的值，**圆点**才用亮色。
 *
 * 对比度（相对各自胶囊底色，白底页面上计算）：
 * - done    #186b42 / #e8f2ec ≈ 5.6:1
 * - running #2f5fc4 / #e9effc ≈ 5.5:1
 * - failed  #b42318 / #fbecea ≈ 5.9:1
 * - warn    #8a5a00 / #faf0d9 ≈ 5.3:1
 */
const STATUS_INK = {
  done: '#186b42',
  running: '#2f5fc4',
  failed: '#b42318',
  warn: '#8a5a00',
  idle: 'var(--color-muted)',
} as const;

/** 与工具卡同一套骨架变量：两个组件都注入这一份，保证是同一族视觉语言。 */
export const toolTimelineBaseVars: Record<string, string> = {
  '--tc-ink': DS.ink,
  /** 正文级次要文本，5.7:1 —— 副行、表头、胶囊之外的说明都用它 */
  '--tc-muted': DS.textSecondary,
  /** 仅供图标 / 折角等图形元素，3.2:1 满足图形对比度，不用于文本 */
  '--tc-faint': 'rgb(140, 145, 155)',
  '--tc-hairline': DS.border,
  /* 脊线要看得见才算层次。0.09 在白底上几乎不可见，等于白做 */
  '--tc-rail': 'rgba(15, 17, 21, 0.16)',
  '--tc-hover': DS.hover,
  '--tc-code-bg': DS.surfaceSoft,
  '--tc-brand': DS.brand,
  '--tc-brand-ink': STATUS_INK.running,
  '--tc-dot-halo': '#ffffff',
  '--tc-dot-pulse': 'color-mix(in srgb, var(--color-primary) 30%, transparent)',
  '--tc-warn': STATUS_INK.warn,
  '--tc-warn-bg': 'rgba(212, 160, 23, 0.16)',
};

/** 每种语气覆盖圆点与胶囊的取色。 */
export const TOOL_TONE_VARS: Record<ToolTone, Record<string, string>> = {
  done: {
    '--tc-dot': '#2f9e63',
    '--tc-chip-ink': STATUS_INK.done,
    '--tc-chip-bg': 'rgba(24, 107, 66, 0.10)',
  },
  running: {
    '--tc-dot': DS.brand,
    '--tc-dot-pulse': 'color-mix(in srgb, var(--color-primary) 30%, transparent)',
    '--tc-chip-ink': STATUS_INK.running,
    '--tc-chip-bg': 'color-mix(in srgb, var(--color-primary) 10%, transparent)',
  },
  failed: {
    '--tc-dot': '#d92d20',
    '--tc-chip-ink': STATUS_INK.failed,
    '--tc-chip-bg': 'rgba(180, 35, 24, 0.10)',
  },
  idle: {
    '--tc-dot': 'rgb(200, 204, 212)',
    '--tc-chip-ink': STATUS_INK.idle,
    '--tc-chip-bg': 'rgba(15, 17, 21, 0.05)',
  },
};

/** 合并成可直接铺到元素上的 style。自定义属性不在 CSSProperties 里，故此处放宽类型。 */
export function toolTimelineStyle(
  tone?: ToolTone,
  extra: Record<string, string> = {},
): Record<string, string> {
  return {
    ...toolTimelineBaseVars,
    ...(tone ? TOOL_TONE_VARS[tone] : null),
    ...extra,
  };
}
