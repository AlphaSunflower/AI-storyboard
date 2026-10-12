/**
 * 对话内容块的导出能力：表格 → Markdown / CSV，代码块 → 带扩展名的源码文件，
 * 图片 → 剪贴板位图（不支持时回退复制链接）。
 *
 * hast 节点在这里只用结构化最小类型描述，模块保持零依赖，便于单测。
 */

export interface HastLike {
  type?: string;
  tagName?: string;
  value?: string;
  properties?: unknown;
  children?: readonly HastLike[];
  position?: { start?: { line?: number } } | undefined;
}

/**
 * 块的稳定序号（用于下载文件名）：优先取源码起始行号，无位置信息时用调用方给的兜底序号。
 * 起点行号天然随源码顺序递增，重渲染也不会漂移。
 */
export function blockOrdinal(node: HastLike | null | undefined, fallback: number): number {
  const line = node?.position?.start?.line;
  return typeof line === 'number' && line > 0 ? line : fallback;
}

const LANGUAGE_EXTENSIONS: Record<string, string> = {
  bash: 'sh',
  c: 'c',
  'c++': 'cpp',
  cpp: 'cpp',
  cs: 'cs',
  csharp: 'cs',
  css: 'css',
  csv: 'csv',
  dart: 'dart',
  diff: 'diff',
  go: 'go',
  golang: 'go',
  graphql: 'graphql',
  html: 'html',
  http: 'http',
  ini: 'ini',
  java: 'java',
  js: 'js',
  javascript: 'js',
  json: 'json',
  jsx: 'jsx',
  kt: 'kt',
  kotlin: 'kt',
  less: 'less',
  lua: 'lua',
  md: 'md',
  markdown: 'md',
  mdx: 'mdx',
  perl: 'pl',
  php: 'php',
  plaintext: 'txt',
  powershell: 'ps1',
  ps1: 'ps1',
  py: 'py',
  python: 'py',
  rb: 'rb',
  ruby: 'rb',
  rs: 'rs',
  rust: 'rs',
  scss: 'scss',
  sh: 'sh',
  shell: 'sh',
  sql: 'sql',
  svelte: 'svelte',
  swift: 'swift',
  text: 'txt',
  toml: 'toml',
  ts: 'ts',
  tsx: 'tsx',
  txt: 'txt',
  typescript: 'ts',
  vue: 'vue',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yml',
  zsh: 'sh',
};

/** 围栏语言 → 下载扩展名；未知与空语言统一落到 txt。 */
export function extensionForLanguage(language: string): string {
  const normalized = language
    .trim()
    .toLowerCase()
    .replace(/^(?:language|lang)-/, '')
    .split(/[\s:,{]/)[0];
  return LANGUAGE_EXTENSIONS[normalized] ?? 'txt';
}

/** 递归取纯文本；<br> 视作换行，注释等无值节点自然忽略。 */
export function hastText(node: HastLike | null | undefined): string {
  if (!node) return '';
  if (node.type === 'text') return node.value ?? '';
  if (node.tagName === 'br') return '\n';
  const children = node.children ?? [];
  let text = '';
  for (const child of children) text += hastText(child);
  return text;
}

function findDescendant(node: HastLike | null | undefined, tagName: string): HastLike | null {
  if (!node) return null;
  if (node.tagName === tagName) return node;
  for (const child of node.children ?? []) {
    const found = findDescendant(child, tagName);
    if (found) return found;
  }
  return null;
}

function collectDescendants(node: HastLike | null | undefined, tagName: string, out: HastLike[] = []): HastLike[] {
  if (!node) return out;
  for (const child of node.children ?? []) {
    if (child.tagName === tagName) out.push(child);
    collectDescendants(child, tagName, out);
  }
  return out;
}

/** 取 <table> 的行列纯文本（含 <br> 换行），忽略 colspan/rowspan。 */
export function tableToRows(node: HastLike | null | undefined): string[][] {
  const table = node?.tagName === 'table' ? node : findDescendant(node, 'table');
  if (!table) return [];
  const rows: string[][] = [];
  for (const row of collectDescendants(table, 'tr')) {
    const cells: string[] = [];
    for (const child of row.children ?? []) {
      if (child.type !== 'element') continue;
      if (child.tagName !== 'td' && child.tagName !== 'th') continue;
      cells.push(hastText(child));
    }
    if (cells.length > 0) rows.push(cells);
  }
  return rows;
}

function csvCell(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

/** RFC4180 风格 CSV，行尾用 CRLF，便于 Excel / WPS 直接打开。 */
export function tableToCsv(rows: string[][]): string {
  return rows.map((row) => row.map(csvCell).join(',')).join('\r\n');
}

function markdownCell(value: string): string {
  return value.replace(/\r?\n/g, '<br>').replace(/\|/g, '\\|');
}

/** 还原成 GFM 表格源码（不保留原对齐语法）。 */
export function tableToMarkdown(rows: string[][]): string {
  if (rows.length === 0) return '';
  const columnCount = Math.max(...rows.map((row) => row.length));
  if (columnCount === 0) return '';
  const normalized = rows.map((row) => Array.from(
    { length: columnCount },
    (_, index) => markdownCell(row[index] ?? ''),
  ));
  const line = (row: string[]) => `| ${row.join(' | ')} |`;
  const [header, ...body] = normalized;
  return [line(header), line(Array.from({ length: columnCount }, () => '---')), ...body.map(line)].join('\n');
}

/** 取 <pre>/<code> 上的围栏语言（language-xxx / lang-xxx），无则返回空串。 */
export function codeBlockLanguage(node: HastLike | null | undefined): string {
  const code = node?.tagName === 'code' ? node : findDescendant(node, 'code');
  const properties = (code?.properties ?? {}) as Record<string, unknown>;
  const raw = properties['className'] ?? properties['class'];
  const className = Array.isArray(raw) ? raw.join(' ') : typeof raw === 'string' ? raw : '';
  const match = /(?:^|\s)(?:language|lang)-([^\s]+)/i.exec(className);
  return match ? match[1].toLowerCase() : '';
}

/** 代码块原文，去掉围栏语法自带的那一个行尾换行。 */
export function codeBlockText(node: HastLike | null | undefined): string {
  const code = node?.tagName === 'code' ? node : findDescendant(node, 'code');
  const text = hastText(code ?? node);
  return text.endsWith('\n') ? text.slice(0, -1) : text;
}

/** 文本落盘：Blob + <a download>；createObjectURL 不可用时退回 data URI。 */
export function downloadTextFile(filename: string, text: string, mime: string): void {
  let href = '';
  let objectUrl = '';
  try {
    objectUrl = URL.createObjectURL(new Blob([text], { type: mime }));
    href = objectUrl;
  } catch {
    href = `data:${mime},${encodeURIComponent(text)}`;
  }
  const link = document.createElement('a');
  link.href = href;
  link.download = filename;
  link.rel = 'noreferrer';
  document.body.appendChild(link);
  link.click();
  link.remove();
  if (!objectUrl) return;
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

/** 复制纯文本；剪贴板不可用时返回 false，不抛错。 */
export async function copyPlainText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export type ImageCopyResult = 'bitmap' | 'url' | 'failed';

async function decodeImage(blob: Blob): Promise<ImageBitmap | HTMLImageElement> {
  if (typeof createImageBitmap === 'function') return await createImageBitmap(blob);
  const objectUrl = URL.createObjectURL(blob);
  try {
    const image = new Image();
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error('图片解码失败'));
      image.src = objectUrl;
    });
    return image;
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

async function toPngBlob(blob: Blob): Promise<Blob> {
  if (blob.type === 'image/png') return blob;
  const source = await decodeImage(blob);
  const canvas = document.createElement('canvas');
  canvas.width = source.width;
  canvas.height = source.height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('画布不可用');
  context.drawImage(source, 0, 0);
  const png = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  if (!png) throw new Error('PNG 转换失败');
  return png;
}

/**
 * 图片复制：优先写 PNG 位图进剪贴板（跨域 / 浏览器不支持时回退复制链接）。
 * data URI 本身即内容，直接复制字符串。
 */
export async function copyImageToClipboard(url: string): Promise<ImageCopyResult> {
  const source = url.trim();
  if (!source) return 'failed';
  if (source.startsWith('data:')) {
    return (await copyPlainText(source)) ? 'url' : 'failed';
  }
  try {
    const response = await fetch(source);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const png = await toPngBlob(await response.blob());
    const ClipboardItemCtor = (globalThis as { ClipboardItem?: typeof ClipboardItem }).ClipboardItem;
    if (!ClipboardItemCtor || !navigator.clipboard?.write) throw new Error('剪贴板不支持位图');
    await navigator.clipboard.write([new ClipboardItemCtor({ 'image/png': png })]);
    return 'bitmap';
  } catch {
    return (await copyPlainText(source)) ? 'url' : 'failed';
  }
}
