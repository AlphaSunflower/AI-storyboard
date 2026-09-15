import { memo } from 'react';
import type { CanvasNode } from '../../types';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';

// text / note / prompt 三个文本类节点共用同一个 textarea body，仅字段名 + 占位符 + aria-label 不同。
// 合并一个组件（避免三份几乎相同的代码），按 data.kind 切分。
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
  const placeholder = kind === 'note' ? '在此记录设计说明…' : kind === 'prompt' ? '在此输入 AI 提示词…' : '在此输入文字…';
  const ariaLabel = kind === 'note' ? '批注内容' : kind === 'prompt' ? '提示词内容' : '文字内容';

  return (
    <textarea
      className="text-node-editor nodrag nowheel"
      aria-label={ariaLabel}
      value={draft.value}
      placeholder={placeholder}
      onChange={(event) => draft.setValue(event.target.value)}
      onFocus={draft.onFocus}
      onBlur={draft.onBlur}
      onCompositionStart={draft.onCompositionStart}
      onCompositionEnd={draft.onCompositionEnd}
    />
  );
}

export const TextNotePromptNode = memo(TextNotePromptNodeComponent);
