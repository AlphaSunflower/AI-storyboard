/**
 * 上下文窗口占用估算（前端轻量路线）。
 *
 * 分子：最近一轮助手回复的 `inputTokens`（后端已含系统提示 + 全部历史 + 当前输入）；
 *       流式进行中用「最近一轮 + 本轮累积」近似当前占用。
 * 分母：模型上下文窗口。网关模型元信息暂无该字段，故在此维护一张表，未知模型回退默认值。
 */

/** 未命中模型表时的回退窗口（多数对话模型常见档位）。 */
export const DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;

/** 已知模型上下文窗口。键做小写匹配，命中即取「最长匹配键」以兼容带日期后缀的模型名。 */
const CONTEXT_WINDOW_BY_MODEL: Record<string, number> = {
  'claude-sonnet-4': 200_000,
  'claude-opus-4': 200_000,
  'claude-3-7': 200_000,
  'claude-3-5': 200_000,
  'gemini-2.5-pro': 1_000_000,
  'gemini-2.5-flash': 1_000_000,
  'gemini-3': 1_000_000,
  'gemini-2.0': 1_000_000,
  'gpt-4.1': 1_000_000,
  'gpt-4o': 128_000,
  'o3': 200_000,
  'o4-mini': 200_000,
  // DeepSeek 全系按 1M 窗口；覆盖 v3 / r1 / flash / chat 等变体。
  'deepseek': 1_000_000,
  'mimo': 1_000_000,
  'qwen3-max': 128_000,
  'kimi-k2': 200_000,
};

/** 模型名 → 上下文窗口；未知模型返回 `DEFAULT_CONTEXT_WINDOW_TOKENS`。 */
export function contextWindowTokensFor(model?: string | null): number {
  if (!model) return DEFAULT_CONTEXT_WINDOW_TOKENS;
  const key = model.trim().toLowerCase();
  if (!key) return DEFAULT_CONTEXT_WINDOW_TOKENS;
  let best: number | null = null;
  let bestLength = 0;
  for (const [prefix, window] of Object.entries(CONTEXT_WINDOW_BY_MODEL)) {
    if (key.includes(prefix) && prefix.length > bestLength) {
      best = window;
      bestLength = prefix.length;
    }
  }
  return best ?? DEFAULT_CONTEXT_WINDOW_TOKENS;
}

/**
 * 已用 token → 占用比例（0..1）。
 * `limit` 非正数时返回 0，避免除零。
 */
export function contextUsageRatio(usedTokens: number, limitTokens: number): number {
  if (!Number.isFinite(limitTokens) || limitTokens <= 0) return 0;
  const used = Number.isFinite(usedTokens) ? Math.max(0, usedTokens) : 0;
  return Math.min(1, used / limitTokens);
}

/**
 * 整千紧凑计数，供悬浮卡展示：70000 → `70k`，1024 → `1k`，640 → `640`。
 * 阈值与设计稿一致（999.5k 起进位到 `1M`）。
 */
export function formatTokenCount(value: number): string {
  const n = Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
  if (n >= 999_500) {
    const millions = n / 1_000_000;
    const rounded = millions < 10 ? Math.round(millions * 10) / 10 : Math.round(millions);
    return `${rounded}M`;
  }
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}
