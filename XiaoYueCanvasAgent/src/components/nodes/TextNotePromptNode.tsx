import { memo, useState } from 'react';
import type { CanvasNode } from '../../types';

// text / note / prompt 三个文本类节点共用同一个 textarea body，仅字段名 + 占位符 + aria-label 不同。
// 合并一个组件（避免三份几乎相同的代码），按 data.kind 切分。
function TextNotePromptNodeComponent({ id, data, updateNode }: {
  id: string;
  data: CanvasNode['data'];
  updateNode: (id: string, patch: Record<string, unknown>) => void;
}) {
  const [textDraft, setTextDraft] = useState<string | undefined>(data.prompt);
  const kind = data.kind;
  const value = kind === 'note' ? (data.richText ?? textDraft) : kind === 'prompt' ? (data.promptText ?? textDraft) : textDraft;
  const field: Record<string, Record<string, unknown>> = {
    note: { richText: value },
    prompt: { promptText: value },
    text: { prompt: value },
  };
  const placeholder = kind === 'note' ? '在此记录设计说明…' : kind === 'prompt' ? '在此输入 AI 提示词…' : '在此输入文字…';
  const ariaLabel = kind === 'note' ? '批注内容' : kind === 'prompt' ? '提示词内容' : '文字内容';

  return (
    <textarea
      className="text-node-editor nodrag nowheel"
      aria-label={ariaLabel}
      value={value}
      placeholder={placeholder}
      onChange={(event) => {
        setTextDraft(event.target.value);
        updateNode(id, { [kind === 'note' ? 'richText' : kind === 'prompt' ? 'promptText' : 'prompt']: event.target.value });
      }}
      onBlur={() => {
        if (kind === 'text' && textDraft !== data.prompt) updateNode(id, { prompt: textDraft });
      }}
    />
  );
}

export const TextNotePromptNode = memo(TextNotePromptNodeComponent);
