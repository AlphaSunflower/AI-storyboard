/**
 * /chat 页设计 token 的唯一真源（warm editorial：珊瑚品牌 + 奶油底）。
 *
 * 独立成模块是为了让输入卡里的每个组件（上下文环、引用胶囊、注释浮层）都能
 * 引用同一份 token，而不是各自硬编码颜色；`DS` 只是它的公开别名。
 * 注意：这里只允许引用 styles/tokens.css 的语义变量，不再出现第二套蓝色主题。
 */
export const agentChatTokens = {
  brand: 'var(--color-primary)',
  brandHover: 'var(--color-primary-active)',
  bubble: 'color-mix(in srgb, var(--color-primary) 9%, var(--color-canvas))',
  ink: 'var(--color-ink)',
  textSecondary: 'var(--color-muted)',
  textCaption: 'var(--color-muted-soft)',
  border: 'var(--color-hairline)',
  borderStrong: 'color-mix(in srgb, var(--color-ink) 14%, transparent)',
  hover: 'color-mix(in srgb, var(--color-primary) 10%, transparent)',
  sidebarBg: 'var(--color-surface-soft)',
  surfaceSoft: 'var(--color-surface-soft)',
  surface: 'var(--color-canvas)',
  warning: 'var(--color-warning)',
  danger: 'var(--color-error)',
  /** 悬浮提示卡（深色）底 + 两级前景 */
  onDark: 'var(--color-on-dark)',
  onDarkSoft: 'var(--color-on-dark-soft)',
  /** 上下文组成三色：系统提示词（中性灰）/ 工具定义（琥珀）/ 对话消息（珊瑚） */
  segSystem: 'var(--color-muted-soft)',
  segTools: 'var(--color-warning)',
  segMessages: 'var(--color-primary)',
  maxContent: 780,
  /** 收缩态输入卡的最大宽度：只留一行输入时横向收窄（对齐浮窗 pill 的宽度）。 */
  maxContentCompact: 480,
} as const;

export type AgentChatTokens = typeof agentChatTokens;
