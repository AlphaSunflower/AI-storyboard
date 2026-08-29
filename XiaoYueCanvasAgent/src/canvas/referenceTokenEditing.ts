/**
 * @图N 参考 token 解析器（纯逻辑，可单测）。
 *
 * 支持的格式（向后兼容参考工程的写法）：
 *  - 新格式：@image#1:filename.png / @audio#1（自带文件名，自描述；当前画布只用图，保留 audio 解析）
 *  - 旧格式：@图1、@1
 *
 * 用于：提示词中引用参考图（RichPromptInput 渲染 chip）、生成前解析引用哪些号、
 * 参考图条点击插入 @图N、删除某号引用时清理 token。
 */

export interface ReferenceTokenMatch {
  /** 文本中的起始索引 */
  start: number;
  /** 结束索引（不含） */
  end: number;
  /** 匹配到的原文（如 @image#1:file.png） */
  text: string;
  /** 引用的编号（1-based） */
  imageNumber: number;
  /** 若为 @image#N:filename 则带出文件名 */
  filename?: string;
  /** 媒体类型 */
  mediaType?: 'image' | 'audio';
}

const isAsciiDigit = (ch: string) => ch >= '0' && ch <= '9';

function tryParsePrefixedToken(text: string, index: number, prefix: '@image#' | '@audio#', maxCount: number): ReferenceTokenMatch | null {
  if (text.substring(index, index + prefix.length) !== prefix) return null;
  let digitsEnd = index + prefix.length;
  while (digitsEnd < text.length && isAsciiDigit(text[digitsEnd])) digitsEnd++;
  if (digitsEnd === index + prefix.length) return null;
  const number = Number(text.slice(index + prefix.length, digitsEnd));
  if (number < 1 || number > maxCount) return null;
  let end = digitsEnd;
  let filename: string | undefined;
  if (end < text.length && text[end] === ':') {
    let fNameEnd = end + 1;
    while (fNameEnd < text.length && !/\s/.test(text[fNameEnd])) fNameEnd++;
    if (fNameEnd > end + 1) filename = text.slice(end + 1, fNameEnd);
    end = fNameEnd;
  }
  return { start: index, end, text: text.slice(index, end), imageNumber: number, filename, mediaType: prefix === '@audio#' ? 'audio' : 'image' };
}

function tryParseLegacyToken(text: string, index: number, maxCount: number): ReferenceTokenMatch | null {
  const hasTu = text[index + 1] === '图';
  const digitsStart = hasTu ? index + 2 : index + 1;
  let digitsEnd = digitsStart;
  while (digitsEnd < text.length && isAsciiDigit(text[digitsEnd])) digitsEnd++;
  if (digitsEnd === digitsStart) return null;
  // 贪心取 ≤ maxCount 的最大合法号
  let bestEnd = -1;
  let bestValue = 0;
  let rolling = 0;
  for (let cursor = digitsStart; cursor < digitsEnd; cursor++) {
    rolling = rolling * 10 + Number(text[cursor]);
    if (rolling >= 1 && rolling <= maxCount) { bestEnd = cursor + 1; bestValue = rolling; }
    if (rolling > maxCount) break;
  }
  if (bestEnd <= 0) return null;
  return { start: index, end: bestEnd, text: text.slice(index, bestEnd), imageNumber: bestValue };
}

/** 找出文本中所有参考 token。 */
export function findReferenceTokens(text: string, maxCount: number = 99): ReferenceTokenMatch[] {
  const matches: ReferenceTokenMatch[] = [];
  for (let index = 0; index < text.length; index++) {
    if (text[index] !== '@') continue;
    const audio = tryParsePrefixedToken(text, index, '@audio#', maxCount);
    if (audio) { matches.push(audio); index = audio.end - 1; continue; }
    const image = tryParsePrefixedToken(text, index, '@image#', maxCount);
    if (image) { matches.push(image); index = image.end - 1; continue; }
    const legacy = tryParseLegacyToken(text, index, maxCount);
    if (legacy) { matches.push(legacy); index = legacy.end - 1; }
  }
  return matches;
}

/** 收集引用的图片编号（去重升序）。 */
export function collectReferencedImageNumbers(text: string, maxCount?: number): number[] {
  const tokens = findReferenceTokens(text, maxCount);
  return [...new Set(tokens.filter((t) => t.mediaType !== 'audio').map((t) => t.imageNumber))].sort((a, b) => a - b);
}

/** 构建一个 @图N token（新格式带引用的文件名）。 */
export function buildReferenceToken(imageNumber: number, filename?: string): string {
  return filename && filename.trim() ? `@image#${imageNumber}:${filename.trim()}` : `@image#${imageNumber}`;
}

/** 在 cursor 处插入 token（自动补空格），返回新文本 + 新光标位。 */
export function insertReferenceToken(text: string, cursor: number, marker: string): { text: string; cursor: number } {
  const before = text.slice(0, cursor);
  const after = text.slice(cursor);
  const needsLeading = before.length > 0 && !/\s/.test(before[before.length - 1]);
  const needsTrailing = after.length > 0 ? !/\s/.test(after[0]) : true;
  const insertion = `${needsLeading ? ' ' : ''}${marker}${needsTrailing ? ' ' : ''}`;
  const newText = text.slice(0, cursor) + insertion + text.slice(cursor);
  return { text: newText, cursor: cursor + insertion.length };
}

/** 移除指定编号的引用 token（清理多余空格）。 */
export function removeReferenceTokenByNumber(text: string, imageNumber: number): string {
  const tokens = findReferenceTokens(text);
  let result = text;
  for (let i = tokens.length - 1; i >= 0; i--) {
    if (tokens[i].imageNumber === imageNumber && tokens[i].mediaType !== 'audio') {
      result = (result.slice(0, tokens[i].start) + result.slice(tokens[i].end)).replace(/\s{2,}/g, ' ').trim();
    }
  }
  return result;
}

/** 把 token 替换成给 AI 读的可读标签（[参考图N]），提交前清洗。 */
export function stripReferenceMarkers(text: string): string {
  return text
    .replace(/@image#(\d+)(?::\S+)?/g, '[参考图$1]')
    .replace(/@audio#(\d+)(?::\S+)?/g, '[参考音频$1]')
    .replace(/@图(\d+)/g, '[参考图$1]')
    .replace(/@(\d+)/g, '[参考图$1]')
    .replace(/\s{2,}/g, ' ')
    .trim();
}
