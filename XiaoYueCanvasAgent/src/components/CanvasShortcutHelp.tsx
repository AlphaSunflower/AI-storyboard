import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Keyboard, X } from 'lucide-react';
import { groupCanvasShortcuts } from '../canvas/canvasShortcuts';
import './CanvasShortcutHelp.css';

/**
 * 画布快捷键表：? 或右上角键盘按钮打开。
 * 节点键位与工具栏、键盘监听共用 `canvasShortcuts` 的同一份顺序。
 */
export function CanvasShortcutHelp({ open, onClose }: { open: boolean; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    closeRef.current?.focus();
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      className="canvas-shortcuts-backdrop"
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="canvas-shortcuts"
        role="dialog"
        aria-modal="true"
        aria-labelledby="canvas-shortcuts-title"
        onPointerDown={(event) => event.stopPropagation()}
      >
        <header className="canvas-shortcuts__head">
          <span className="canvas-shortcuts__icon" aria-hidden="true"><Keyboard size={18} /></span>
          <div>
            <h2 id="canvas-shortcuts-title">画布快捷键</h2>
            <p>不在输入框内时生效；节点可先点选，再按快捷键。</p>
          </div>
          <button ref={closeRef} type="button" onClick={onClose} aria-label="关闭快捷键帮助">
            <X size={16} />
          </button>
        </header>

        <div className="canvas-shortcuts__body">
          {groupCanvasShortcuts().map(({ group, items }) => (
            <section className="canvas-shortcuts__group" key={group}>
              <h3>{group}</h3>
              <div className="canvas-shortcuts__grid">
                {items.map((item) => (
                  <div className="canvas-shortcuts__row" key={`${group}-${item.key}`}>
                    <kbd>{item.key}</kbd>
                    <span>
                      <strong>{item.label}</strong>
                      <small>{item.description}</small>
                    </span>
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>

        <footer className="canvas-shortcuts__foot">
          <span>输入框聚焦时快捷键自动让位；Esc 关闭</span>
          <button type="button" onClick={onClose}>知道了</button>
        </footer>
      </section>
    </div>,
    document.body,
  );
}
