import { useEffect, useRef, useState } from 'react';
import { useCanvasStore } from '../../store/canvasStore';
import { nodeKindIcon } from '../../canvas/nodeKindIcon';

/**
 * 节点外置标题：贴在卡片上方（不占卡片内部空间），图标 + 可编辑标题。
 *
 * 反向缩放：节点本身被画布按 zoom 放大/缩小，标题用 1/zoom 抵消，
 * 配合 transform-origin: left bottom，标题在任何缩放级别都保持屏幕尺寸不变，
 * 并且始终以左下角贴着节点顶部。缩放系数由画布以 --inverse-zoom 变量下发，
 * 这样标题不需要每个节点都订阅 zoom。
 */
export function NodeExternalTitle({ nodeId, label, kind, dragHandle = false }: {
  nodeId: string;
  label: string;
  kind: string;
  /** 标题条是否带抓取光标。注意：整块节点本来就是拖动面（见 CanvasWorkspace.flowNodes），
     这个 prop 现在只影响 cursor 提示，不再决定「能不能拖」。 */
  dragHandle?: boolean;
}) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(label);
  const inputRef = useRef<HTMLInputElement>(null);
  const Icon = nodeKindIcon(kind);
  const text = label?.trim() || kind;

  useEffect(() => {
    if (!editing) setDraft(text);
  }, [editing, text]);

  useEffect(() => {
    if (!editing) return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [editing]);

  const commit = () => {
    const next = draft.trim();
    if (next && next !== label) updateNode(nodeId, { label: next });
    else setDraft(text);
    setEditing(false);
  };

  return (
    <div className={`ai-node-external-title${dragHandle ? ' node-drag-handle' : ''}`}>
      <span className="ai-node-icon"><Icon size={17} strokeWidth={2} /></span>
      {editing ? (
        <input
          ref={inputRef}
          className="ai-node-title-input nodrag"
          aria-label="节点标题"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            if (event.key === 'Enter') { event.preventDefault(); commit(); }
            if (event.key === 'Escape') { event.preventDefault(); setDraft(text); setEditing(false); }
          }}
        />
      ) : (
        <div
          className="ai-node-title is-editable"
          title={text}
          onDoubleClick={(event) => { event.stopPropagation(); setDraft(text); setEditing(true); }}
        >{text}</div>
      )}
    </div>
  );
}
