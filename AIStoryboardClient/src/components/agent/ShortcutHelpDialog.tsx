import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Keyboard, X } from 'lucide-react';
import { groupMoonShortcuts } from '../../lib/moonShortcuts';
import './ShortcutHelpDialog.css';

/**
 * Moon 智能体快捷键表。由侧栏键盘按钮或 Ctrl/Cmd + / 打开，
 * 与 `moonShortcuts.ts` 共用同一份键位，不在组件里硬编码第二套。
 */
export function ShortcutHelpDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    closeRef.current?.focus();
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      className="moon-shortcuts-backdrop"
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="moon-shortcuts"
        role="dialog"
        aria-modal="true"
        aria-labelledby="moon-shortcuts-title"
        onPointerDown={(event) => event.stopPropagation()}
      >
        <header className="moon-shortcuts__head">
          <span className="moon-shortcuts__icon" aria-hidden="true"><Keyboard size={18} strokeWidth={1.9} /></span>
          <div>
            <h2 id="moon-shortcuts-title">键盘快捷键</h2>
            <p>在 Moon 智能体里随时可用，输入框聚焦时同样生效。</p>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="moon-shortcuts__close"
            onClick={onClose}
            aria-label="关闭快捷键帮助"
          ><X size={16} strokeWidth={2} /></button>
        </header>

        <div className="moon-shortcuts__body">
          {groupMoonShortcuts().map(({ group, items }) => (
            <section className="moon-shortcuts__group" key={group}>
              <h3>{group}</h3>
              <dl>
                {items.map((item) => (
                  <div className="moon-shortcuts__row" key={item.id}>
                    <dt>
                      <span>{item.label}</span>
                      <small>{item.description}</small>
                    </dt>
                    <dd><kbd>{item.keys}</kbd></dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>

        <footer className="moon-shortcuts__foot">
          <span>macOS 可用 Cmd 代替 Ctrl</span>
          <button type="button" onClick={onClose}>知道了</button>
        </footer>
      </section>
    </div>,
    document.body,
  );
}
