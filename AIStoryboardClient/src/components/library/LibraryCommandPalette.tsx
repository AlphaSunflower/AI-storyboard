import { useEffect, useMemo, useRef, useState } from 'react';
import { Command, CornerDownLeft, Search, Sparkles } from 'lucide-react';
import type { LibraryAsset, LibrarySavedView } from '../../api/library';
import './LibraryCommandPalette.css';

export interface LibraryCommandAction {
  id: string;
  label: string;
  hint?: string;
  group?: string;
  run: () => void;
}

interface LibraryCommandPaletteProps {
  open: boolean;
  onClose: () => void;
  actions: LibraryCommandAction[];
  assets: LibraryAsset[];
  views: LibrarySavedView[];
  onOpenAsset: (asset: LibraryAsset) => void;
  onApplyView: (view: LibrarySavedView) => void;
}

type PaletteItem =
  | { kind: 'action'; key: string; label: string; hint: string; run: () => void }
  | { kind: 'asset'; key: string; label: string; hint: string; asset: LibraryAsset }
  | { kind: 'view'; key: string; label: string; hint: string; view: LibrarySavedView };

/** Cmd/Ctrl+K 命令面板：动作、素材、保存视图统一搜索。 */
export function LibraryCommandPalette({
  open,
  onClose,
  actions,
  assets,
  views,
  onOpenAsset,
  onApplyView,
}: LibraryCommandPaletteProps) {
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setActiveIndex(0);
    const frame = window.requestAnimationFrame(() => inputRef.current?.focus());
    return () => window.cancelAnimationFrame(frame);
  }, [open]);

  const items = useMemo<PaletteItem[]>(() => {
    const term = query.trim().toLowerCase();
    const match = (text: string) => !term || text.toLowerCase().includes(term);
    const result: PaletteItem[] = [];
    actions.filter((action) => match(action.label) || match(action.hint || ''))
      .slice(0, 10)
      .forEach((action) => result.push({
        kind: 'action', key: `action:${action.id}`, label: action.label,
        hint: action.hint || action.group || '动作', run: action.run,
      }));
    views.filter((view) => match(view.name))
      .slice(0, 6)
      .forEach((view) => result.push({
        kind: 'view', key: `view:${view.id}`, label: view.name,
        hint: '保存视图', view,
      }));
    assets.filter((asset) => match(asset.name) || match(asset.aiDescription || '') || match(asset.description || ''))
      .slice(0, 12)
      .forEach((asset) => result.push({
        kind: 'asset', key: `asset:${asset.id}`, label: asset.name,
        hint: `${asset.type}${asset.folderPath?.length ? ` · ${asset.folderPath.join(' / ')}` : ''}`,
        asset,
      }));
    return result;
  }, [actions, assets, query, views]);

  useEffect(() => {
    setActiveIndex((current) => Math.min(current, Math.max(0, items.length - 1)));
  }, [items.length]);

  useEffect(() => {
    if (!open) return;
    const list = listRef.current;
    if (!list) return;
    const active = list.querySelector<HTMLElement>(`[data-index="${activeIndex}"]`);
    active?.scrollIntoView?.({ block: 'nearest' });
  }, [activeIndex, open]);

  if (!open) return null;

  const runItem = (item: PaletteItem | undefined) => {
    if (!item) return;
    if (item.kind === 'action') item.run();
    else if (item.kind === 'asset') onOpenAsset(item.asset);
    else onApplyView(item.view);
    onClose();
  };

  return (
    <div className="library-command-backdrop" role="presentation" onMouseDown={onClose}>
      <div
        className="library-command"
        role="dialog"
        aria-modal="true"
        aria-label="项目库命令面板"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <Search size={16} />
          <input
            ref={inputRef}
            value={query}
            placeholder="搜索素材、视图或执行动作…"
            aria-label="搜索素材、视图或动作"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown') {
                event.preventDefault();
                setActiveIndex((current) => (items.length === 0 ? 0 : (current + 1) % items.length));
              } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                setActiveIndex((current) => (items.length === 0 ? 0 : (current - 1 + items.length) % items.length));
              } else if (event.key === 'Enter') {
                event.preventDefault();
                runItem(items[activeIndex]);
              } else if (event.key === 'Escape') {
                event.preventDefault();
                onClose();
              }
            }}
          />
          <kbd>ESC</kbd>
        </header>
        <div className="library-command__list" ref={listRef} role="listbox">
          {items.length === 0 ? (
            <div className="library-command__empty">
              <Sparkles size={18} /> 没有匹配的动作、素材或视图
            </div>
          ) : items.map((item, index) => (
            <button
              key={item.key}
              type="button"
              role="option"
              aria-selected={index === activeIndex}
              data-index={index}
              className={index === activeIndex ? 'is-active' : ''}
              onMouseEnter={() => setActiveIndex(index)}
              onClick={() => runItem(item)}
            >
              <span className={`library-command__kind is-${item.kind}`}>
                {item.kind === 'action' ? <Command size={13} /> : item.kind === 'asset' ? '素材' : '视图'}
              </span>
              <span className="library-command__label">{item.label}</span>
              <small>{item.hint}</small>
              {index === activeIndex && <CornerDownLeft size={12} />}
            </button>
          ))}
        </div>
        <footer>
          <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
          <span><kbd>Enter</kbd> 打开</span>
          <span><kbd>⌘K</kbd> 关闭</span>
        </footer>
      </div>
    </div>
  );
}
