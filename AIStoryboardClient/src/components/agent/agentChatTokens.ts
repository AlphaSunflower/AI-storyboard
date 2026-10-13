/**
 * /chat 页设计 token 的唯一真源（DeepSeek 风格：蓝色品牌 + 中性灰底）。
 *
 * 独立成模块是为了让输入卡里的每个组件（上下文环、引用胶囊、注释浮层）都能
 * 引用同一份 token，而不是各自硬编码颜色；`DS` 只是它的公开别名。
 */
export const agentChatTokens = {
  brand: 'rgb(65, 118, 230)',
  brandHover: 'rgb(86, 134, 254)',
  bubble: 'rgb(235, 240, 250)',
  ink: 'rgb(15, 17, 21)',
  textSecondary: 'rgb(96, 100, 108)',
  textCaption: 'rgb(152, 158, 168)',
  border: 'rgba(0, 0, 0, 0.08)',
  borderStrong: 'rgba(0, 0, 0, 0.10)',
  hover: 'rgba(65, 118, 230, 0.06)',
  sidebarBg: '#f7f8fa',
  surfaceSoft: 'rgb(249, 250, 251)',
  surface: '#ffffff',
  warning: 'rgb(212, 160, 23)',
  danger: 'rgb(217, 45, 32)',
  /** 悬浮提示卡（深色）底 + 两级前景 */
  onDark: 'rgb(250, 249, 245)',
  onDarkSoft: 'rgb(160, 157, 150)',
  /** 上下文组成三色：系统提示词（中性灰）/ 工具定义（紫）/ 对话消息（品牌蓝） */
  segSystem: 'rgb(150, 154, 162)',
  segTools: 'rgb(139, 122, 246)',
  segMessages: 'rgb(65, 118, 230)',
  maxContent: 780,
  /** 收缩态输入卡的最大宽度：只留一行输入时横向收窄（对齐浮窗 pill 的宽度）。 */
  maxContentCompact: 480,
} as const;

export type AgentChatTokens = typeof agentChatTokens;
