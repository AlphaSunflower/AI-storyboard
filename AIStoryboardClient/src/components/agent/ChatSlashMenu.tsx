import { useEffect, useMemo, useState } from 'react';
import { agentChatTokens as T } from './agentChatTokens';

export interface SlashCommandItem {
  id: string;
  icon: React.ReactNode;
  title: string;
  description: string;
  /** 移动端或内嵌侧边聊天中可隐藏整项。 */
  visible?: boolean;
  /** 不可用项：仍展示（可发现），但不可执行并显示 disabledHint */
  disabled?: boolean;
  disabledHint?: string;
  run: () => void;
}

/**
 * 输入框斜杠命令面板（Codex 风格）：输入「/」时在输入卡上方列出命令。
 *
 * <p>注册表式：新增命令 = ChatComposer 里往 items 数组追加一项，本组件不用改。
 * 键盘（↑↓ / Enter / Tab / Esc）由本组件在 window 上捕获，避免与 textarea 抢焦点；
 * ChatComposer 在命令模式下只负责阻止 Enter 换行。
 */
export function ChatSlashMenu({
  items, query, onClose,
}: {
  items: SlashCommandItem[];
  query: string;
  onClose: () => void;
}) {
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    const available = items.filter((item) => item.visible !== false);
    if (!q) return available;
    return available.filter((item) => item.title.toLowerCase().includes(q)
      || item.id.toLowerCase().includes(q));
  }, [items, query]);
  const [active, setActive] = useState(0);

  useEffect(() => { setActive(0); }, [query]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
      if (visible.length === 0) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((i) => (i + 1) % visible.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((i) => (i - 1 + visible.length) % visible.length);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        const item = visible[active];
        if (!item || item.disabled) return;
        e.preventDefault();
        item.run();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [visible, active, onClose]);

  if (visible.length === 0) return null;

  return (
    <div className="chat-slash-menu" role="listbox" aria-label="命令" style={{
      position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, right: 0,
      background: 'white', border: `1px solid ${T.border}`,
      borderRadius: 14, boxShadow: '0 8px 28px rgba(0,0,0,0.14)',
      padding: 6, zIndex: 1100, display: 'flex', flexDirection: 'column', gap: 2,
    }}>
      {visible.map((item, index) => (
        <button
          key={item.id}
          type="button"
          role="option"
          aria-selected={index === active}
          onMouseEnter={() => setActive(index)}
          onClick={() => { if (!item.disabled) item.run(); }}
          disabled={item.disabled}
          style={{
            display: 'flex', alignItems: 'center', gap: 10, width: '100%',
            padding: '9px 10px', border: 'none', borderRadius: 10, textAlign: 'left',
            background: index === active && !item.disabled ? T.hover : 'transparent',
            color: item.disabled ? T.textCaption : T.ink,
            cursor: item.disabled ? 'not-allowed' : 'pointer',
          }}
        >
          <span style={{ display: 'flex', flexShrink: 0, color: item.disabled ? T.textCaption : T.brand }}>
            {item.icon}
          </span>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 13, fontWeight: 600 }}>{item.title}</span>
            <span style={{ display: 'block', fontSize: 12, color: T.textCaption, marginTop: 1 }}>
              {item.disabled ? (item.disabledHint ?? item.description) : item.description}
            </span>
          </span>
        </button>
      ))}
    </div>
  );
}
