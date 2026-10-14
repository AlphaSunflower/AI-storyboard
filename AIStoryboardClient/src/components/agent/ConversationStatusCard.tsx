import { agentChatTokens as T } from './agentChatTokens';
import { formatApproxTokenCount, formatTokenCount } from './contextWindow';
import type { ConversationStatus } from './contextStatus';

/**
 * 额度占位：后续接入每人图片/视频生成额度时，换成后端字段即可。
 * ponytail: 常量占位，接 quota 接口时删掉它、改成 prop。
 */
const QUOTA_PLACEHOLDER = '不可用';

export interface ConversationStatusCardProps {
  conversationId: string | null;
  /** 与底部状态栏同一份聚合口径（contextStatus.summarizeConversationStatus），不另算一套 */
  status: ConversationStatus;
  onClose: () => void;
}

/**
 * 「状态」浮层卡（/chat 斜杠命令）：会话 ID / 上下文占用 / 运行统计 / 缓存 / 额度。
 *
 * 宽度跟输入卡一致（`width: auto` + 左右贴边）：这张卡挂在输入卡内部做绝对定位，
 * 而共用的 `.chat-status-card` 把宽度钉在 236px（那是底部状态栏那张深色小卡的尺寸），
 * 这里必须显式覆盖，否则卡片会比输入框窄一大截。
 */
export function ConversationStatusCard({
  conversationId, status, onClose,
}: ConversationStatusCardProps) {
  const percent = status.contextWindowTokens > 0
    ? Math.round((status.contextTokens / status.contextWindowTokens) * 100)
    : 0;

  const rows: Array<[string, string]> = [
    ['会话/对话串', conversationId ?? '（未选择对话）'],
    ['背景信息', status.hasData
      ? `剩余 ${Math.max(0, 100 - percent)}%（已使用 ${formatTokenCount(status.contextTokens)}/共 ${formatTokenCount(status.contextWindowTokens)}）`
      : '暂无数据（尚未发生模型调用）'],
  ];
  if (status.hasData) {
    rows.push([
      '运行统计',
      `${status.rounds} 轮 ${status.steps} 步 · 输出 ${Math.round(status.tokensPerSecond)} tok/s`,
    ]);
    rows.push([
      '缓存',
      `命中 ${Math.round(status.cacheHitRatio * 100)}%（读取 ${formatApproxTokenCount(status.cachedInputTokens)} tok）`,
    ]);
  }
  rows.push(['额度', QUOTA_PLACEHOLDER]);

  return (
    <div className="chat-status-card" style={{
      position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, right: 0,
      width: 'auto',
      background: 'white', border: `1px solid ${T.border}`, borderRadius: 14,
      boxShadow: '0 8px 28px rgba(0,0,0,0.14)', padding: '12px 14px', zIndex: 1100,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 13, color: T.textSecondary, fontWeight: 600 }}>状态</span>
        <button
          type="button"
          onClick={onClose}
          style={{
            border: 'none', background: 'transparent', color: T.textSecondary,
            fontSize: 13, cursor: 'pointer', padding: '2px 4px',
          }}
        >关闭</button>
      </div>
      {rows.map(([label, value]) => (
        <div key={label} style={{ display: 'flex', gap: 10, fontSize: 13, lineHeight: 1.5 }}>
          <span style={{ width: 88, flexShrink: 0, color: T.textSecondary }}>{label}:</span>
          <span style={{
            flex: 1, minWidth: 0, color: T.ink, wordBreak: 'break-all',
            fontFamily: label === '会话/对话串'
              ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : 'inherit',
          }}>{value}</span>
        </div>
      ))}
    </div>
  );
}
