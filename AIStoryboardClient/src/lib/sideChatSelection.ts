import type { SideChatSelection } from '../stores/sideChatSelectionStore';

const CONTEXT_START = '<selected-text-context>';
const CONTEXT_END = '</selected-text-context>';
const CONTEXT_POLICY = '用户指定了以下文本片段作为回答上下文。请优先围绕这些片段作答；如果片段不足以确定答案，请明确说明。';

export function buildSideChatSelectionContext(
  selections: SideChatSelection[],
  userText: string,
): string {
  const selected = selections
    .map((selection) => selection.text.trim())
    .filter(Boolean);
  if (selected.length === 0) return userText.trim();

  const context = selected
    .map((text, index) => `【片段 ${index + 1}】\n${text}`)
    .join('\n\n');
  const request = userText.trim() || '请结合以上选中的文本片段回答。';

  return [
    '用户指定了以下文本片段作为回答上下文。请优先围绕这些片段作答；如果片段不足以确定答案，请明确说明。',
    '',
    '<selected-text-context>',
    context,
    '</selected-text-context>',
    '',
    request,
  ].join('\n');
}

export function parseSideChatSelectionContext(content: string): {
  userText: string;
  selections: string[];
} {
  const start = content.indexOf(CONTEXT_START);
  const end = content.indexOf(CONTEXT_END);
  if (start < 0 || end < start) {
    return { userText: content, selections: [] };
  }
  const block = content.slice(start + CONTEXT_START.length, end);
  const selections = block
    .split(/\n(?=【片段 \d+】)/)
    .map((item) => item.replace(/^【片段 \d+】\s*/, '').trim())
    .filter(Boolean);
  const userText = `${content.slice(0, start)}${content.slice(end + CONTEXT_END.length)}`
    .replace(CONTEXT_POLICY, '')
    .trim();
  return { userText, selections };
}
