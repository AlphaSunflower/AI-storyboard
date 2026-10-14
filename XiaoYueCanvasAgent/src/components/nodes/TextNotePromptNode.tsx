import { memo, useCallback, useEffect, useRef, useState } from 'react';
import type { CanvasNode } from '../../types';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';

// text / note / prompt 三个文本类节点共用同一个正文，仅字段名 + 占位符 + aria-label 不同。
// 合并一个组件（避免三份几乎相同的代码），按 data.kind 切分。
//
// 正文默认渲染「静态预览面」而不是常驻 textarea：输入框必须挂 .nodrag（否则按下去拖的是节点、
// 选不了字），于是整块正文都失去拖动能力，节点只能靠标题条挪位置。
// 改成预览面之后整块都是画布拖动面，想改字双击才进输入态，失焦或 Esc 退出。
function TextNotePromptNodeComponent({ id, data, updateNode }: {
  id: string;
  data: CanvasNode['data'];
  updateNode: (id: string, patch: Record<string, unknown>) => void;
}) {
  const kind = data.kind;
  const field = kind === 'note' ? 'richText' : kind === 'prompt' ? 'promptText' : 'prompt';
  const value = String(data[field] ?? '');
  const draft = useNodeTextDraft({
    nodeId: `${id}:${field}`,
    value,
    onChange: (next) => updateNode(id, { [field]: next }),
  });
  const [editing, setEditing] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);
  // Esc 退出时 textarea 会被卸载，React 不会为卸载元素补 blur，focus 会掉到 document.body
  // （焦点丢失 + 键盘没法接着 Enter/F2 回编辑态）。用这个标记把焦点交还给预览面。
  // 失焦退出不置位：那时用户已经点到别处，抢回焦点是错的。
  const restoreFocusRef = useRef(false);
  const placeholder = kind === 'note' ? '在此记录设计说明…' : kind === 'prompt' ? '在此输入 AI 提示词…' : '在此输入文字…';
  const ariaLabel = kind === 'note' ? '批注内容' : kind === 'prompt' ? '提示词内容' : '文字内容';

  // 进入编辑态把光标落到末尾：双击一般是想接着写，不是想覆盖全文。
  useEffect(() => {
    if (!editing) return;
    const element = textareaRef.current;
    if (!element) return;
    element.focus();
    element.setSelectionRange(element.value.length, element.value.length);
  }, [editing]);

  // 退出前必须走 draft.onBlur()：它除了 flush 还会把 focusedRef 放掉，
  // 否则节点后续被外部改写（撤销、其它面板写入）时草稿不会再同步回来。
  const { onBlur: flushDraft } = draft;
  const exitEditing = useCallback(() => {
    flushDraft();
    setEditing(false);
  }, [flushDraft]);

  useEffect(() => {
    if (editing || !restoreFocusRef.current) return;
    restoreFocusRef.current = false;
    previewRef.current?.focus();
  }, [editing]);

  if (!editing) {
    return (
      <div
        ref={previewRef}
        className={`text-node-preview nowheel${draft.value ? '' : ' is-empty'}`}
        role="textbox"
        aria-readonly="true"
        aria-label={ariaLabel}
        tabIndex={0}
        title="拖动移动节点，双击编辑"
        onDoubleClick={(event) => { event.stopPropagation(); setEditing(true); }}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== 'F2') return;
          event.preventDefault();
          setEditing(true);
        }}
      >{draft.value || placeholder}</div>
    );
  }

  return (
    <textarea
      ref={textareaRef}
      className="text-node-editor nodrag nowheel"
      aria-label={ariaLabel}
      value={draft.value}
      placeholder={placeholder}
      onChange={(event) => draft.setValue(event.target.value)}
      onFocus={draft.onFocus}
      onBlur={exitEditing}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        restoreFocusRef.current = true;
        exitEditing();
      }}
      onCompositionStart={draft.onCompositionStart}
      onCompositionEnd={draft.onCompositionEnd}
    />
  );
}

export const TextNotePromptNode = memo(TextNotePromptNodeComponent);
