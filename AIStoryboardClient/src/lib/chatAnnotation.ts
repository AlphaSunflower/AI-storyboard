/**
 * /chat 消息内注释（选中文字 → 评论）的纯逻辑：
 * - 发送上下文编解码（拼进正文 / 渲染时剥离，不改后端接口）
 * - 在已渲染的消息 DOM 里定位被引用的原文区间（只读，不改 React 管理的 DOM）
 */

export const ANNOTATION_BLOCK_START = '【引用注释】';
export const ANNOTATION_BLOCK_END = '【/引用注释】';
export const ANNOTATION_NO_COMMENT = '无';

export interface AnnotationContextInput {
  quote: string;
  comment?: string;
}

function normalizeQuote(quote: string): string {
  return quote.replace(/\r\n/g, '\n').trim();
}

/** 把注释按固定格式拼到用户输入前面；无有效注释时原样返回用户输入。 */
export function buildAnnotationContext(annotations: AnnotationContextInput[], userText: string): string {
  const text = userText.trim();
  const items = annotations
    .map((annotation) => ({
      quote: normalizeQuote(annotation.quote),
      comment: (annotation.comment ?? '').trim(),
    }))
    .filter((item) => item.quote);
  if (items.length === 0) return text;

  const lines: string[] = [ANNOTATION_BLOCK_START];
  items.forEach((item, index) => {
    const quoteLines = item.quote.split('\n');
    lines.push(`${index + 1}. 原文：${quoteLines[0]}`);
    quoteLines.slice(1).forEach((extra) => lines.push(`   ${extra}`));
    lines.push(`   评论：${item.comment || ANNOTATION_NO_COMMENT}`);
  });
  lines.push(ANNOTATION_BLOCK_END);
  const block = lines.join('\n');
  return text ? `${block}\n\n${text}` : block;
}

/** 剥掉消息正文最前面的注释上下文块，返回用户真正输入的文字。 */
export function stripAnnotationContext(content: string): { userText: string; hasContext: boolean } {
  const normalized = content.replace(/\r\n/g, '\n');
  if (!normalized.startsWith(ANNOTATION_BLOCK_START)) return { userText: content, hasContext: false };
  const endIndex = normalized.indexOf(ANNOTATION_BLOCK_END);
  if (endIndex < 0) return { userText: content, hasContext: false };
  return {
    userText: normalized.slice(endIndex + ANNOTATION_BLOCK_END.length).replace(/^\n+/, ''),
    hasContext: true,
  };
}

interface TextSegment {
  node: Text;
  start: number;
  end: number;
}

interface TextMap {
  text: string;
  segments: TextSegment[];
}

interface NormalizedText {
  text: string;
  /** 归一化后每个字符在原始文本里的下标。 */
  originIndex: number[];
}

const ANNOTATION_UI_SELECTOR = '[data-annotation-ui]';
const SKIPPED_TAGS = new Set(['STYLE', 'SCRIPT', 'NOSCRIPT']);
const BLOCK_TAGS = new Set([
  'ADDRESS', 'ARTICLE', 'ASIDE', 'BLOCKQUOTE', 'DIV', 'DL', 'DT', 'DD', 'FIELDSET',
  'FIGCAPTION', 'FIGURE', 'FOOTER', 'FORM', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'HEADER', 'HR', 'LI', 'MAIN', 'NAV', 'OL', 'P', 'PRE', 'SECTION', 'TABLE', 'TD',
  'TH', 'TR', 'UL',
]);

function shouldSkipTextNode(node: Text): boolean {
  const parent = node.parentElement;
  if (!parent) return true;
  if (SKIPPED_TAGS.has(parent.tagName)) return true;
  return Boolean(parent.closest(ANNOTATION_UI_SELECTOR));
}

function nearestBlock(node: Text, root: HTMLElement): Element | null {
  let element = node.parentElement;
  while (element && element !== root) {
    if (BLOCK_TAGS.has(element.tagName)) return element;
    element = element.parentElement;
  }
  return null;
}

/** 收集消息正文里的文本节点，拼成一份带映射的纯文本。 */
function collectTextMap(root: HTMLElement): TextMap {
  const segments: TextSegment[] = [];
  let text = '';
  let lastBlock: Element | null = null;
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let current = walker.nextNode();
  while (current) {
    const node = current as Text;
    if (!shouldSkipTextNode(node)) {
      // 块级元素之间补一个换行，否则跨段选区的文字会黏在一起，归一化后匹配不到
      const block = nearestBlock(node, root);
      if (lastBlock && block && block !== lastBlock && !text.endsWith('\n')) text += '\n';
      segments.push({ node, start: text.length, end: text.length + node.data.length });
      text += node.data;
      lastBlock = block;
    }
    current = walker.nextNode();
  }
  return { text, segments };
}

/** 把连续空白折叠成单个空格，同时记录每个字符回到原文的下标。 */
function normalizeWithMap(raw: string): NormalizedText {
  let text = '';
  const originIndex: number[] = [];
  let previousWasSpace = false;
  for (let i = 0; i < raw.length; i += 1) {
    const char = raw[i];
    if (/\s/.test(char)) {
      if (!previousWasSpace) {
        text += ' ';
        originIndex.push(i);
        previousWasSpace = true;
      }
    } else {
      text += char;
      originIndex.push(i);
      previousWasSpace = false;
    }
  }
  return { text, originIndex };
}

function locateOffset(segments: TextSegment[], offset: number): { node: Text; offset: number } | null {
  if (segments.length === 0) return null;
  for (const segment of segments) {
    if (offset >= segment.start && offset <= segment.end) {
      return { node: segment.node, offset: offset - segment.start };
    }
  }
  const last = segments[segments.length - 1];
  return { node: last.node, offset: last.node.data.length };
}

/** 在消息正文里找到第 occurrence 处 quote 对应的 DOM Range；找不到返回 null。 */
export function findQuoteRange(root: HTMLElement, quote: string, occurrence = 0): Range | null {
  const target = normalizeWithMap(normalizeQuote(quote)).text;
  if (!target) return null;
  const map = collectTextMap(root);
  if (!map.text) return null;
  const normalized = normalizeWithMap(map.text);

  let from = 0;
  let found = -1;
  for (let i = 0; i <= Math.max(0, occurrence); i += 1) {
    found = normalized.text.indexOf(target, from);
    if (found < 0) return null;
    from = found + target.length;
  }

  const startOffset = normalized.originIndex[found];
  const endOffset = normalized.originIndex[found + target.length - 1] + 1;
  const start = locateOffset(map.segments, startOffset);
  const end = locateOffset(map.segments, endOffset);
  if (!start || !end) return null;

  const range = root.ownerDocument.createRange();
  range.setStart(start.node, start.offset);
  range.setEnd(end.node, end.offset);
  return range;
}

function offsetOfRangeStart(segments: TextSegment[], range: Range): number | null {
  const container = range.startContainer;
  if (container.nodeType !== Node.TEXT_NODE) return null;
  const node = container as Text;
  for (const segment of segments) {
    if (segment.node === node) return segment.start + range.startOffset;
  }
  return null;
}

/** 用户这次选中的是同一段文字里的第几处（0-based），用于高亮定位。 */
export function selectionOccurrence(root: HTMLElement, range: Range, quote: string): number | undefined {
  const target = normalizeWithMap(normalizeQuote(quote)).text;
  if (!target) return undefined;
  const map = collectTextMap(root);
  const selectionStart = offsetOfRangeStart(map.segments, range);
  if (selectionStart == null) return undefined;

  const normalized = normalizeWithMap(map.text);
  let normalizedSelectionStart = normalized.originIndex.length;
  for (let i = 0; i < normalized.originIndex.length; i += 1) {
    if (normalized.originIndex[i] >= selectionStart) {
      normalizedSelectionStart = i;
      break;
    }
  }

  let count = 0;
  let from = 0;
  for (;;) {
    const index = normalized.text.indexOf(target, from);
    if (index < 0 || index >= normalizedSelectionStart) break;
    count += 1;
    from = index + target.length;
  }
  return count;
}
