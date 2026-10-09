import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MessageSquare, Pencil, Trash2, X } from 'lucide-react';
import type { ChatAnnotation } from '../../stores/chatAnnotationStore';
import { popoverPosition, toAnchorRect, type AnchorRect } from './annotationPopover';
import { agentChatTokens as T } from './agentChatTokens';

/** 注释列表浮层：逐条显示所选原文 + 评论，右侧可编辑 / 删除。 */
export function AnnotationListPopover({
  annotations,
  anchor,
  onEdit,
  onDelete,
  onPointerEnter,
  onPointerLeave,
}: {
  annotations: ChatAnnotation[];
  anchor: AnchorRect;
  onEdit: (annotation: ChatAnnotation) => void;
  onDelete: (id: string) => void;
  onPointerEnter?: () => void;
  onPointerLeave?: () => void;
}) {
  return createPortal(
    <div
      data-annotation-ui
      data-annotation-list
      role="dialog"
      aria-label="注释列表"
      onMouseEnter={onPointerEnter}
      onMouseLeave={onPointerLeave}
      style={{
        position: 'fixed', ...popoverPosition(anchor, 340), width: 340, maxHeight: 320, overflowY: 'auto', zIndex: 2250,
        background: T.surface, borderRadius: 16, padding: 8,
        border: `1px solid ${T.border}`, boxShadow: '0 12px 32px rgba(15,17,21,0.16)',
        display: 'flex', flexDirection: 'column', gap: 6,
      }}
    >
      {annotations.map((annotation, index) => (
        <div key={annotation.id} style={{ padding: '10px 12px', borderRadius: 12, background: T.surfaceSoft }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 12, color: T.textSecondary }}>{index + 1}。 所选文本：</div>
              <div style={{
                marginTop: 2, fontSize: 13, lineHeight: 1.55, color: T.ink,
                whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              }}>
                {annotation.quote}
              </div>
              {annotation.comment && (
                <div style={{ marginTop: 6, fontSize: 12, lineHeight: 1.5, color: T.textSecondary }}>
                  评论：{annotation.comment}
                </div>
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
              <button
                type="button"
                aria-label={`编辑注释 ${index + 1}`}
                onClick={() => onEdit(annotation)}
                style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: T.textSecondary, padding: 4, display: 'grid', placeItems: 'center' }}
              >
                <Pencil size={14} strokeWidth={1.9} />
              </button>
              <button
                type="button"
                aria-label={`删除注释 ${index + 1}`}
                onClick={() => onDelete(annotation.id)}
                style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: T.textSecondary, padding: 4, display: 'grid', placeItems: 'center' }}
              >
                <Trash2 size={14} strokeWidth={1.9} />
              </button>
            </div>
          </div>
        </div>
      ))}
    </div>,
    document.body,
  );
}

/**
 * 「N 条注释」胶囊（按消息汇总）：悬停展开列表，胶囊自身悬停出现 ×（清空该消息注释）。
 * 放在输入框上方，作为发送前的注释汇总入口。
 */
export function AnnotationChip({
  annotations,
  onEdit,
  onDelete,
  onClear,
}: {
  annotations: ChatAnnotation[];
  onEdit: (annotation: ChatAnnotation) => void;
  onDelete: (id: string) => void;
  onClear: () => void;
}) {
  const [hovered, setHovered] = useState(false);
  const [listAnchor, setListAnchor] = useState<AnchorRect | null>(null);
  const chipRef = useRef<HTMLSpanElement>(null);
  const closeTimer = useRef<number | null>(null);

  const openList = useCallback(() => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
    if (chipRef.current) setListAnchor(toAnchorRect(chipRef.current.getBoundingClientRect()));
  }, []);

  const scheduleClose = useCallback(() => {
    if (closeTimer.current !== null) window.clearTimeout(closeTimer.current);
    closeTimer.current = window.setTimeout(() => setListAnchor(null), 160);
  }, []);

  useEffect(() => () => {
    if (closeTimer.current !== null) window.clearTimeout(closeTimer.current);
  }, []);

  return (
    <>
      <span
        ref={chipRef}
        data-annotation-ui
        onMouseEnter={() => { setHovered(true); openList(); }}
        onMouseLeave={() => { setHovered(false); scheduleClose(); }}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 2,
          padding: '1px 3px 1px 8px', borderRadius: 999,
          border: `1px solid ${T.borderStrong}`, background: T.surface,
        }}
      >
        <button
          type="button"
          aria-label={`${annotations.length} 条注释`}
          onClick={() => { if (listAnchor) setListAnchor(null); else openList(); }}
          style={{
            border: 'none', background: 'transparent', cursor: 'pointer',
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 12, color: T.textSecondary, padding: '3px 2px',
          }}
        >
          <MessageSquare size={12} strokeWidth={1.9} />
          {annotations.length} 条注释
        </button>
        {hovered && (
          <button
            type="button"
            aria-label="清空该消息全部注释"
            onClick={onClear}
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              color: T.textSecondary, display: 'grid', placeItems: 'center',
              padding: 3, borderRadius: 999,
            }}
          >
            <X size={11} strokeWidth={2.2} />
          </button>
        )}
      </span>
      {listAnchor && annotations.length > 0 && (
        <AnnotationListPopover
          annotations={annotations}
          anchor={listAnchor}
          // 点编辑先收起列表，再让上层把编辑框定位到被引用的原文处
          onEdit={(annotation) => { setListAnchor(null); onEdit(annotation); }}
          onDelete={onDelete}
          onPointerEnter={openList}
          onPointerLeave={scheduleClose}
        />
      )}
    </>
  );
}
