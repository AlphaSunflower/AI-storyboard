import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Search, X } from 'lucide-react';
import { agentApi, type AgentConversation } from '../../api/agent';
import { useProjectStore } from '../../stores/projectStore';
import { DS } from './ChatComposer';
import { usePresence } from '../../hooks/usePresence';

interface SearchHit {
  conv: AgentConversation;
  field: 'title' | 'message';
  /** 展示片段（已压缩换行/空白） */
  snippet: string;
  /** 用于匹配的原始内容（不截断） */
  raw: string;
  ts: number;
}

/** 断文片段：截取关键词前后上下文，首尾用 … 表示截断 */
function snippetAround(raw: string, q: string, before = 22, after = 52): string {
  const text = raw.replace(/\s+/g, ' ').trim();
  const idx = text.toLowerCase().indexOf(q.toLowerCase());
  if (idx < 0) return text.slice(0, after + before);
  const start = Math.max(0, idx - before);
  const end = Math.min(text.length, idx + q.length + after);
  return `${start > 0 ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}`;
}

function timeLabel(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const ts = d.getTime();
  const yesterday = today - 86400000;
  const weekAgo = today - 7 * 86400000;
  if (ts >= today) return '今天';
  if (ts >= yesterday) return '昨天';
  if (ts >= weekAgo) return '7天内';
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

/** 高亮关键词（蓝色 <mark> 样式），逐段拆分避免 REGEX 转义问题 */
function Highlight({ text, q }: { text: string; q: string }) {
  if (!q) return <>{text}</>;
  const parts = text.split(q);
  return (
    <>
      {parts.map((p, i) => (
        <span key={i}>
          {p}
          {i < parts.length - 1 && (
            <span style={{ color: DS.brand, fontWeight: 600 }}>{q}</span>
          )}
        </span>
      ))}
    </>
  );
}

/**
 * 全文 + 标题搜索弹窗：
 * 打开时拉取当前项目全部会话 + 每条消息建索引，输入即本地过滤。
 * 点击结果选中对应会话并关闭；× 清空；点击遮罩 / Esc 关闭。
 */
export function ConversationSearchModal({ open, onClose, onSelectConversation }: {
  open: boolean;
  onClose: () => void;
  onSelectConversation: (id: string) => void;
}) {
  const projectId = useProjectStore((s) => s.currentProject?.id);
  const [query, setQuery] = useState('');
  const [index, setIndex] = useState<SearchHit[]>([]);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { mounted, visible, closing } = usePresence(open, 220);

  // 打开：拉取会话 + 消息建索引
  useEffect(() => {
    if (!open) { setQuery(''); setIndex([]); return; }
    setQuery('');
    setIndex([]);
    if (!projectId) { setLoading(false); return; }
    setLoading(true);
    (async () => {
      try {
        const convRes = await agentApi.listConversations(projectId);
        const convs = convRes.data.data ?? [];
        const idx: SearchHit[] = [];
        for (const conv of convs) {
          idx.push({ conv, field: 'title', snippet: conv.title, raw: conv.title, ts: new Date(conv.updatedAt).getTime() });
          try {
            const msgRes = await agentApi.listMessages(conv.id);
            const msgs = msgRes.data.data ?? [];
            for (const m of msgs) {
              idx.push({ conv, field: 'message', snippet: m.content.replace(/\s+/g, ' ').trim(), raw: m.content, ts: new Date(m.createdAt).getTime() });
            }
          } catch { /* 单会话消息拉取失败跳过 */ }
        }
        setIndex(idx);
      } catch { /* 静默 */ }
      setLoading(false);
    })();
  }, [open, projectId]);

  useEffect(() => { if (open) setTimeout(() => inputRef.current?.focus(), 30); }, [open]);

  // Esc / 遮罩关闭
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, [open, onClose]);

  const q = query.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!q || index.length === 0) return [];
    return index
      .filter((h) => h.raw.toLowerCase().includes(q))
      .slice()
      .sort((a, b) => b.ts - a.ts);
  }, [q, index]);

  if (!mounted) return null;

  return createPortal(
    <div
      onClick={onClose}
      className={`chat-modal-backdrop${visible ? ' is-open' : ''}${closing ? ' is-closing' : ''}`}
      style={{
        position: 'fixed', inset: 0, zIndex: 500,
        background: 'rgba(20, 20, 19, 0.55)',
        display: 'flex', justifyContent: 'center', alignItems: 'flex-start',
        paddingTop: 72,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className={`chat-modal-surface${visible ? ' is-open' : ''}${closing ? ' is-closing' : ''}`}
        style={{
          width: 'min(640px, 92vw)', maxHeight: '70vh',
          background: 'white', borderRadius: 16, boxShadow: '0 24px 72px rgba(0,0,0,0.3)',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        {/* 搜索输入行 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '16px 18px', borderBottom: '1px solid var(--color-hairline)' }}>
          <Search size={18} strokeWidth={1.8} color={DS.brand} />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索对话内容..."
            style={{
              flex: 1, border: 'none', outline: 'none', background: 'transparent',
              fontSize: 16, color: DS.ink, fontFamily: 'inherit',
            }}
          />
          {query && (
            <button
              onClick={() => setQuery('')}
              title="清空"
              style={{
                border: 'none', background: 'var(--color-surface-soft)', color: 'var(--color-muted)',
                width: 26, height: 26, borderRadius: '50%', cursor: 'pointer',
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              }}
            ><X size={14} strokeWidth={2} /></button>
          )}
          <button
            onClick={onClose}
            title="关闭搜索"
            aria-label="关闭搜索"
            style={{
              border: 'none', background: 'transparent', color: 'var(--color-muted)',
              width: 28, height: 28, borderRadius: 8, cursor: 'pointer',
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            }}
          ><X size={16} strokeWidth={1.8} /></button>
        </div>

        {/* 结果区 */}
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '8px 10px 12px' }}>
          {loading ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-muted)', fontSize: 14 }}>搜索中…</div>
          ) : !q ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14 }}>
              搜索会话标题与消息内容
            </div>
          ) : matches.length === 0 ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14 }}>
              未找到「{query.trim()}」相关对话
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {matches.map((h, i) => (
                <button
                  key={`${h.conv.id}-${i}`}
                  className="project-dropdown-item"
                  onClick={() => { onSelectConversation(h.conv.id); onClose(); }}
                  style={{
                    '--item-index': Math.min(i, 8),
                    display: 'block', width: '100%', textAlign: 'left', cursor: 'pointer',
                    border: 'none', background: 'transparent', padding: '10px 10px', borderRadius: 12,
                    transition: 'background 0.15s',
                  } as React.CSSProperties}
                  onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span style={{ fontSize: 14, fontWeight: 600, color: DS.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {h.conv.title}
                    </span>
                    <span style={{ fontSize: 12, color: 'var(--color-muted)', flexShrink: 0 }}>{timeLabel(new Date(h.ts).toISOString())}</span>
                  </div>
                  <div style={{ fontSize: 12.5, color: 'var(--color-muted-soft)', marginTop: 2, lineHeight: 1.5 }}>
                    {h.field === 'title' ? '标题匹配：' : ''}<Highlight text={snippetAround(h.raw, query.trim())} q={query.trim()} />
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
