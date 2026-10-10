import { agentChatTokens as T } from './agentChatTokens';
import { formatTokenCount } from './contextWindow';

/**
 * 额度占位：后续接入每人图片/视频生成额度时，换成后端字段即可。
 * ponytail: 常量占位，接 quota 接口时删掉它、改成 prop。
 */
const QUOTA_PLACEHOLDER = '不可用';

export interface ConversationStatusCardProps {
  conversationId: string | null;
  usedTokens: number;
  limitTokens: number;
  hasContextData: boolean;
  onClose: () => void;
}

/**
 * 「状态」浮层卡（/chat 斜杠命令）：会话 ID / 背景信息（上下文额度）/ 额度。
 * 数据全部复用输入卡已有计算，不新增后端接口。
 */
export function ConversationStatusCard({
  conversationId, usedTokens, limitTokens, hasContextData, onClose,
}: ConversationStatusCardProps) {
  const percent = limitTokens > 0 ? Math.round((usedTokens / limitTokens) * 100) : 0;
  const rows: Array<[string, string]> = [
    ['会话/对话串', conversationId ?? '（未选择对话）'],
    ['背景信息', hasContextData
      ? `剩余 ${Math.max(0, 100 - percent)}%（已使用 ${formatTokenCount(usedTokens)}/共 ${formatTokenCount(limitTokens)}）`
      : '暂无数据（尚未发生模型调用）'],
    ['额度', QUOTA_PLACEHOLDER],
  ];
  return (
    <div className="chat-status-card" style={{
      position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, right: 0,
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
