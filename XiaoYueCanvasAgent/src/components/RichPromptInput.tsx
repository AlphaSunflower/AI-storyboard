import { memo, useCallback, useEffect, useRef, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  findReferenceTokens,
  insertReferenceToken,
  buildReferenceToken,
  removeReferenceTokenByNumber,
} from '../canvas/referenceTokenEditing';
import type { ReferenceImageEntry, ReferenceImagePoolResult } from '../canvas/referenceImagePool';
import { ReferenceStrip } from './ReferenceStrip';

/**
 * 即梦式富提示词输入：contenteditable，@图N token 渲染成内联 chip。
 * 核心对齐参考工程 RichPromptInput，但做了两处适配：
 *  - 当前画布是 Blob 资产模型（assetId→mediaStore blob→objectURL 需异步 hook），
 *    而 contenteditable 的 chip 需要同步 URL——Ponytail 取舍：chip 不放缩略图，
 *    只渲染文字 @N:filename；缩略图由上方 ReferenceStrip（独立 hook）承担。
 *    若需 chip 缩略图，需引入 blob→objectURL 缓存层，另行说明。
 *  - 用一条 contenteditable div 承载显示+输入（参考 v3 采用的做法，规避双层对齐问题）。
 */

export interface RichPromptInputProps {
  value: string;
  onChange: (value: string) => void;
  onKeyDown?: (e: React.KeyboardEvent) => void;
  onFocus?: () => void;
  onCursorPosChange?: (pos: number) => void;
  placeholder?: string;
  disabled?: boolean;
  maxLength?: number;
  pool: ReferenceImagePoolResult;
  className?: string;
  style?: React.CSSProperties;
  minHeight?: number;
  maxHeight?: number;
  /** 是否显示上方参考图条（默认 false） */
  showStrip?: boolean;
  onDeleteRefEntry?: (entry: ReferenceImageEntry) => void;
}

// ─── helpers ──────────────────────────────────────────────────────────────

function truncateFilename(name: string, maxLen = 10): string {
  if (name.length <= maxLen) return name;
  const dot = name.lastIndexOf('.');
  if (dot > 0 && dot > name.length - 8) {
    const ext = name.slice(dot);
    const keep = maxLen - ext.length - 1;
    if (keep > 3) return name.slice(0, keep) + '…' + ext;
  }
  return name.slice(0, maxLen - 1) + '…';
}

function escapeHTML(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** 遍历 contenteditable，把 text 节点 + data-token chip 还原成 value 字符串。 */
function extractValueFromDOM(element: HTMLElement): string {
  let value = '';
  const children = Array.from(element.childNodes);
  for (let i = 0; i < children.length; i++) {
    const node = children[i];
    if (node.nodeType === Node.TEXT_NODE) {
      value += node.textContent || '';
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const el = node as HTMLElement;
      if (el.dataset.token) {
        value += el.dataset.token;
        // token 后紧跟非空白文本时补空格，防 filename 解析吞掉后续文字
        if (i + 1 < children.length) {
          const next = children[i + 1];
          const nextText = next.nodeType === Node.TEXT_NODE ? (next.textContent || '') : '';
          if (nextText.length > 0 && !/^\s/.test(nextText)) value += ' ';
        }
      } else if (el.tagName === 'BR') {
        value += '\n';
      } else {
        value += extractValueFromDOM(el);
      }
    }
  }
  return value;
}

function saveCursorOffset(element: HTMLElement): number {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return 0;
  const range = sel.getRangeAt(0);
  const preRange = range.cloneRange();
  preRange.selectNodeContents(element);
  preRange.setEnd(range.startContainer, range.startOffset);
  const frag = preRange.cloneContents();
  const temp = document.createElement('div');
  temp.appendChild(frag);
  return extractValueFromDOM(temp).length;
}

function restoreCursorOffset(element: HTMLElement, offset: number) {
  const sel = window.getSelection();
  if (!sel) return;
  const range = document.createRange();
  let currentOffset = 0;
  function walk(node: Node): boolean {
    if (currentOffset >= offset) return true;
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent || '';
      if (currentOffset + text.length >= offset) {
        range.setStart(node, offset - currentOffset);
        range.collapse(true);
        return true;
      }
      currentOffset += text.length;
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const el = node as HTMLElement;
      if (el.dataset.token) {
        const tokenLen = (el.dataset.token || '').length;
        if (currentOffset + tokenLen >= offset) {
          range.setStartAfter(el);
          range.collapse(true);
          return true;
        }
        currentOffset += tokenLen;
      } else {
        for (const child of Array.from(node.childNodes)) if (walk(child)) return true;
      }
    }
    return false;
  }
  walk(element);
  sel.removeAllRanges();
  sel.addRange(range);
}

// ─── 内联 @picker（对齐参考 ReferencePicker：键盘导航 + 过滤 + 上游 tab） ─────────
function ReferencePicker({
  entries,
  onSelect,
  onClose,
  filterText = '',
}: {
  entries: ReferenceImageEntry[];
  onSelect: (n: number) => void;
  onClose: () => void;
  filterText?: string;
}) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);

  const filtered = useMemo(() => {
    const f = filterText.toLowerCase();
    return f ? entries.filter((e) => (e.sourceNodeName || `图${e.number}`).toLowerCase().includes(f)) : entries;
  }, [entries, filterText]);

  useEffect(() => setSelectedIndex(0), [filtered.length, filterText]);

  useEffect(() => {
    const handle = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown') { e.preventDefault(); setSelectedIndex((p) => (p + 1) % Math.max(1, filtered.length)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); setSelectedIndex((p) => (p - 1 + filtered.length) % Math.max(1, filtered.length)); }
      else if (e.key === 'Enter') { e.preventDefault(); const entry = filtered[selectedIndex]; if (entry) onSelect(entry.number); }
      else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
    };
    document.addEventListener('keydown', handle);
    return () => document.removeEventListener('keydown', handle);
  }, [filtered, selectedIndex, onSelect, onClose]);

  useEffect(() => {
    const outside = (e: MouseEvent) => { if (containerRef.current && !containerRef.current.contains(e.target as Node)) onClose(); };
    const t = setTimeout(() => document.addEventListener('mousedown', outside), 100);
    return () => { clearTimeout(t); document.removeEventListener('mousedown', outside); };
  }, [onClose]);

  if (!entries.length) {
    return (
      <div ref={containerRef} className="reference-picker reference-picker--empty">
        <div>暂无可用素材</div>
        <small>请先连接包含图片的上游节点</small>
      </div>
    );
  }
  if (!filtered.length) {
    return (
      <div ref={containerRef} className="reference-picker reference-picker--empty">
        <div>无匹配结果</div>
        <small>「{filterText}」未匹配到任何素材</small>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="reference-picker">
      <div className="reference-picker__label">参考素材</div>
      <div className="reference-picker__grid">
        {filtered.map((entry, index) => (
          <button
            key={entry.number}
            className={`reference-picker__cell ${index === selectedIndex ? 'is-selected' : ''}`}
            onClick={() => onSelect(entry.number)}
            onMouseEnter={() => setSelectedIndex(index)}
          >
            <span className="reference-picker__num">@{entry.number}</span>
            <span className="reference-picker__name">{entry.sourceNodeName || `图${entry.number}`}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── RichPromptInput ─────────────────────────────────────────────────────

export const RichPromptInput = memo(function RichPromptInput({
  value,
  onChange,
  onKeyDown,
  onFocus,
  onCursorPosChange,
  placeholder = '',
  disabled = false,
  maxLength: _maxLength,
  pool,
  className = '',
  style,
  minHeight: _minHeight = 120,
  maxHeight = 320,
  showStrip = false,
  onDeleteRefEntry,
}: RichPromptInputProps) {
  const editableRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isComposingRef = useRef(false);
  const skipNextUpdateRef = useRef(false);
  const lastValueRef = useRef(value);
  const cursorPosRef = useRef(0);

  const [showPicker, setShowPicker] = useState(false);
  const [pickerAnchorRect, setPickerAnchorRect] = useState<DOMRect | null>(null);
  const atPositionRef = useRef<number | null>(null);

  // 当前引用的图号集合（strip 高亮）
  const referencedNumbers = useMemo(() => new Set(findReferenceTokens(value, pool.count).map((t) => t.imageNumber)), [value, pool.count]);

  const buildHTML = useCallback((val: string): string => {
    const tokens = findReferenceTokens(val, pool.count);
    if (tokens.length === 0) return escapeHTML(val);
    let html = '';
    let lastIndex = 0;
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      if (token.start > lastIndex) html += escapeHTML(val.slice(lastIndex, token.start));
      const entry = pool.getByNumber(token.imageNumber);
      if (entry) {
        const raw = entry.sourceNodeName || `图${token.imageNumber}`;
        const label = escapeHTML(truncateFilename(raw));
        html += `<span contenteditable="false" data-token="${escapeHTML(token.text)}" data-image-number="${token.imageNumber}" data-media-type="${token.mediaType || 'image'}" class="rt-chip nodrag" style="display:inline-flex;align-items:center;gap:4px;background:rgba(215,255,84,0.16);border:1px solid rgba(215,255,84,0.35);border-radius:6px;padding:1px 6px 1px 8px;font-size:12px;line-height:20px;color:var(--acid);font-weight:500;white-space:nowrap;vertical-align:middle;user-select:none;margin:0 1px">${label}<span data-chip-delete style="display:inline-flex;align-items:center;justify-content:center;width:14px;height:14px;border-radius:50%;font-size:10px;color:var(--acid);cursor:pointer;margin-left:2px;opacity:0.7">×</span></span>`;
      } else {
        html += `<span style="background:rgba(234,88,12,0.15);border:1px solid rgba(234,88,12,0.3);border-radius:4px;padding:0 4px;color:var(--text-muted);text-decoration:line-through;font-size:12px">${escapeHTML(token.text)}</span>`;
      }
      lastIndex = token.end;
    }
    if (lastIndex < val.length) html += escapeHTML(val.slice(lastIndex));
    return html;
  }, [pool]);

  const buildHTMLRef = useRef(buildHTML);
  buildHTMLRef.current = buildHTML;

  const readDOMValue = useCallback((): string => (editableRef.current ? extractValueFromDOM(editableRef.current) : value), [value]);

  const applyValue = useCallback((newValue: string, cursorPos?: number) => {
    const el = editableRef.current;
    if (el) el.innerHTML = buildHTMLRef.current(newValue);
    skipNextUpdateRef.current = true;
    lastValueRef.current = newValue;
    onChange(newValue);
    if (cursorPos !== undefined) {
      requestAnimationFrame(() => { if (el) { el.focus(); restoreCursorOffset(el, cursorPos); } });
    }
  }, [onChange]);

  // 首次渲染 + 外部 value 变化同步到 DOM
  const isInitializedRef = useRef(false);
  useEffect(() => {
    const el = editableRef.current;
    if (!el) return;
    if (!isInitializedRef.current) { el.innerHTML = buildHTMLRef.current(value); lastValueRef.current = value; isInitializedRef.current = true; return; }
    if (skipNextUpdateRef.current) { skipNextUpdateRef.current = false; lastValueRef.current = value; return; }
    const currentDOM = extractValueFromDOM(el);
    if (currentDOM !== value) { el.innerHTML = buildHTMLRef.current(value); lastValueRef.current = value; }
  }, [value]);

  const handleInput = useCallback(() => {
    if (isComposingRef.current) return;
    const el = editableRef.current;
    if (!el) return;
    const newValue = extractValueFromDOM(el);
    cursorPosRef.current = saveCursorOffset(el);
    onCursorPosChange?.(cursorPosRef.current);
    if (newValue !== value) {
      skipNextUpdateRef.current = true;
      lastValueRef.current = newValue;
      onChange(newValue);
    }
  }, [value, onChange, onCursorPosChange]);

  const handleClick = useCallback((e: React.MouseEvent) => {
    const target = e.target as HTMLElement;
    const del = target.closest('[data-chip-delete]') as HTMLElement | null;
    if (del) {
      e.preventDefault(); e.stopPropagation();
      const chip = del.closest('[data-token]') as HTMLElement | null;
      if (chip) {
        const num = Number(chip.dataset.imageNumber);
        const current = readDOMValue();
        const tokens = findReferenceTokens(current, pool.count);
        const token = tokens.find((t) => t.imageNumber === num);
        if (token) {
          let next = current.slice(0, token.start) + current.slice(token.end);
          next = next.replace(/\s{2,}/g, ' ').trim();
          applyValue(next);
        }
      }
    }
  }, [readDOMValue, applyValue, pool.count]);

  // 裸 @ 检测 → 弹出 picker
  useEffect(() => {
    const pos = cursorPosRef.current;
    let found: number | null = null;
    for (let i = pos - 1; i >= Math.max(0, pos - 50); i--) {
      const ch = value[i];
      if (ch === '@') { found = i; break; }
      if (ch === ' ' || ch === '\n' || ch === '\t') break;
    }
    if (found !== null && !showPicker) { atPositionRef.current = found; openPicker(); }
    else if (found === null && showPicker) { setShowPicker(false); atPositionRef.current = null; }
    else if (found !== null && showPicker) { atPositionRef.current = found; }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, showPicker]);

  const openPicker = useCallback(() => {
    const container = containerRef.current;
    if (!container) return;
    const rect = container.getBoundingClientRect();
    setPickerAnchorRect({ left: rect.left, bottom: rect.bottom + 4, top: rect.bottom + 4, right: rect.left } as unknown as DOMRect);
    setShowPicker(true);
  }, []);

  // strip 点击插入
  const handleStripInsertRef = useCallback((num: number) => {
    const el = editableRef.current;
    if (el && document.activeElement !== el) {
      el.focus();
      const last = cursorPosRef.current;
      if (last > 0) restoreCursorOffset(el, last);
    }
    const current = readDOMValue();
    const entry = pool.getByNumber(num);
    const marker = buildReferenceToken(num, entry?.sourceNodeName);
    const tokens = findReferenceTokens(current, pool.count);
    const existing = tokens.find((t) => t.imageNumber === num);
    if (existing) {
      let next = current.slice(0, existing.start) + current.slice(existing.end);
      next = next.replace(/\s{2,}/g, ' ').trim();
      applyValue(next);
      return;
    }
    const result = insertReferenceToken(current, cursorPosRef.current, marker);
    applyValue(result.text, result.cursor);
  }, [readDOMValue, applyValue, pool]);

  const handleStripDeleteRef = useCallback((entry: ReferenceImageEntry) => {
    const current = readDOMValue();
    applyValue(removeReferenceTokenByNumber(current, entry.number));
    onDeleteRefEntry?.(entry);
  }, [readDOMValue, applyValue, onDeleteRefEntry]);

  const handlePickerSelect = useCallback((num: number) => {
    setShowPicker(false);
    const current = readDOMValue();
    const entry = pool.getByNumber(num);
    const marker = buildReferenceToken(num, entry?.sourceNodeName);
    if (atPositionRef.current !== null) {
      const atPos = atPositionRef.current;
      let end = atPos + 1;
      while (end < current.length && !/[\s@\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/.test(current[end])) end++;
      const after = current.slice(end);
      const needsTrailing = after.length > 0 && !/\s/.test(after[0]);
      const next = `${current.slice(0, atPos)}${marker}${needsTrailing ? ' ' : ''}${after}`;
      applyValue(next, atPos + marker.length + (needsTrailing ? 1 : 0));
    } else {
      const result = insertReferenceToken(current, current.length, marker);
      applyValue(result.text, result.cursor);
    }
    atPositionRef.current = null;
  }, [readDOMValue, applyValue, pool]);

  const handlePickerClose = useCallback(() => {
    if (atPositionRef.current !== null) {
      const atPos = atPositionRef.current;
      const current = readDOMValue();
      if (current[atPos] === '@') {
        let end = atPos + 1;
        while (end < current.length && /[\w\u4e00-\u9fff\d]/.test(current[end])) end++;
        setShowPicker(false);
        atPositionRef.current = null;
        if (end > atPos + 1) applyValue(current.slice(0, atPos) + current.slice(end));
        return;
      }
    }
    setShowPicker(false);
    atPositionRef.current = null;
  }, [readDOMValue, applyValue]);

  const filterText = useMemo(() => {
    if (!showPicker || atPositionRef.current === null) return '';
    const atPos = atPositionRef.current + 1;
    let end = atPos;
    while (end < value.length && !/[\s@\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/.test(value[end])) end++;
    return value.slice(atPos, end);
  }, [showPicker, value]);

  const handleKeyDownInternal = useCallback((e: React.KeyboardEvent) => {
    const el = editableRef.current;
    if (el) { cursorPosRef.current = saveCursorOffset(el); onCursorPosChange?.(cursorPosRef.current); }
    if (showPicker) {
      if (e.key === 'Escape') { setShowPicker(false); atPositionRef.current = null; e.preventDefault(); return; }
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown' || e.key === 'Enter') { e.preventDefault(); return; }
    }
    onKeyDown?.(e);
  }, [showPicker, onKeyDown, onCursorPosChange]);

  const hasReferences = pool.entries.length > 0;

  return (
    <div
      ref={containerRef}
      className={`rt-input ${className}`}
      style={{ position: 'relative', minHeight: `${_minHeight}px`, display: 'flex', flexDirection: 'column', borderRadius: '10px', padding: 0, ...style }}
    >
      {showStrip && hasReferences && (
        <ReferenceStrip entries={pool.entries} referencedNumbers={referencedNumbers} onInsertRef={handleStripInsertRef} onDeleteRef={handleStripDeleteRef} />
      )}
      <div style={{ position: 'relative', flex: 1, minHeight: `${_minHeight}px`, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div
          ref={editableRef}
          contentEditable={!disabled}
          suppressContentEditableWarning
          onClick={handleClick}
          onInput={handleInput}
          onKeyDown={handleKeyDownInternal}
          onFocus={() => { const el = editableRef.current; if (el) { cursorPosRef.current = saveCursorOffset(el); onCursorPosChange?.(cursorPosRef.current); } onFocus?.(); }}
          onBlur={() => { const el = editableRef.current; if (el) { cursorPosRef.current = saveCursorOffset(el); onCursorPosChange?.(cursorPosRef.current); } }}
          onCompositionStart={() => { isComposingRef.current = true; }}
          onCompositionEnd={() => {
            isComposingRef.current = false;
            const el = editableRef.current;
            if (el) {
              const newValue = extractValueFromDOM(el);
              cursorPosRef.current = saveCursorOffset(el);
              onCursorPosChange?.(cursorPosRef.current);
              if (newValue !== value) { skipNextUpdateRef.current = true; lastValueRef.current = newValue; onChange(newValue); }
            }
          }}
          onPaste={(e) => {
            e.preventDefault();
            let text = e.clipboardData.getData('text/plain');
            text = text.replace(/@(?:image|audio)#\d+:[^\s]*/g, '').replace(/\s{2,}/g, ' ').trim();
            document.execCommand('insertText', false, text);
          }}
          className="rt-input__editable nodrag nowheel"
          style={{
            fontSize: '14px', lineHeight: '22px', fontFamily: 'inherit', width: '100%',
            wordBreak: 'break-word', whiteSpace: 'pre-wrap', outline: 'none', border: 'none',
            borderRadius: '6px', padding: '6px 8px', minHeight: `${Math.max(_minHeight - 12, 40)}px`, flex: 1,
            ...(maxHeight > 0 ? { maxHeight: `${maxHeight}px` } : {}), overflow: 'auto',
            color: 'var(--text-primary)', caretColor: 'var(--text-primary)', opacity: disabled ? 0.5 : 1,
            backgroundColor: 'rgba(19,34,30,.55)',
          }}
        />
        {!value && (
          <div style={{ position: 'absolute', top: '6px', left: '8px', fontSize: '14px', lineHeight: '22px', color: 'rgba(238,240,223,.4)', pointerEvents: 'none', padding: 0 }}>
            {placeholder}
          </div>
        )}
      </div>
      {showPicker && createPortal(
        <div style={{ position: 'fixed', left: (pickerAnchorRect?.left ?? 0), top: (pickerAnchorRect?.top ?? 0) + 2, zIndex: 999 }} className="nodrag">
          <ReferencePicker entries={pool.entries} onSelect={handlePickerSelect} onClose={handlePickerClose} filterText={filterText} />
        </div>,
        document.body
      )}
    </div>
  );
});
