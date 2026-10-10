import { assetUrl } from '../config';
import { authorizedFetch } from './authSession';

export type DocumentPreviewKind =
  | 'docx'
  | 'pptx'
  | 'ppt'
  | 'image'
  | 'markdown'
  | 'text'
  | 'json'
  | 'html'
  | 'pdf'
  | 'unsupported';

const MARKDOWN_EXTENSIONS = new Set(['md', 'markdown', 'mdown', 'mkd']);
const TEXT_EXTENSIONS = new Set([
  'txt', 'text', 'log', 'csv', 'tsv', 'yaml', 'yml', 'xml', 'ini',
  'conf', 'config', 'properties', 'sql', 'srt', 'vtt',
]);
const JSON_EXTENSIONS = new Set(['json', 'jsonl', 'ndjson']);

const localSources = new Map<string, Blob>();
const LOCAL_SOURCE_LIMIT = 8;

function extensionOf(value: string | undefined): string {
  if (!value) return '';
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    // URL 中可能包含未转义的百分号，保留原值继续判断。
  }
  const path = decoded.split(/[?#]/, 1)[0];
  const fileName = path.split(/[\\/]/).pop() ?? '';
  const dot = fileName.lastIndexOf('.');
  return dot >= 0 ? fileName.slice(dot + 1).toLowerCase() : '';
}

function normalizedMime(value: string | undefined): string {
  return (value ?? '').toLowerCase().split(';', 1)[0].trim();
}

export function documentPreviewKind(input: {
  name?: string;
  url?: string;
  mimeType?: string;
}): DocumentPreviewKind {
  const mime = normalizedMime(input.mimeType);
  const extension = extensionOf(input.name) || extensionOf(input.url);

  if (
    extension === 'docx'
    || mime.includes('wordprocessingml.document')
  ) {
    return 'docx';
  }
  if (
    extension === 'pptx'
    || mime.includes('presentationml.presentation')
  ) {
    return 'pptx';
  }
  if (
    extension === 'ppt'
    || mime.includes('application/vnd.ms-powerpoint')
  ) {
    return 'ppt';
  }
  if (extension === 'pdf' || mime === 'application/pdf') return 'pdf';
  if (extension === 'svg' || mime === 'image/svg+xml') return 'image';
  if (extension === 'html' || extension === 'htm' || mime === 'text/html') return 'html';
  if (MARKDOWN_EXTENSIONS.has(extension) || mime.includes('markdown')) return 'markdown';
  if (JSON_EXTENSIONS.has(extension) || mime.includes('json')) return 'json';
  if (
    TEXT_EXTENSIONS.has(extension)
    || mime.startsWith('text/')
    || mime === 'application/xml'
    || mime === 'application/yaml'
  ) {
    return 'text';
  }
  return 'unsupported';
}

export function documentMimeType(input: {
  name?: string;
  url?: string;
  mimeType?: string;
}): string {
  const provided = normalizedMime(input.mimeType);
  if (provided) return provided;

  switch (extensionOf(input.name) || extensionOf(input.url)) {
    case 'docx':
      return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    case 'pptx':
      return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
    case 'ppt':
      return 'application/vnd.ms-powerpoint';
    case 'pdf':
      return 'application/pdf';
    case 'svg':
      return 'image/svg+xml';
    case 'html':
    case 'htm':
      return 'text/html';
    case 'md':
    case 'markdown':
    case 'mdown':
    case 'mkd':
      return 'text/markdown';
    case 'csv':
      return 'text/csv';
    case 'json':
    case 'jsonl':
    case 'ndjson':
      return 'application/json';
    default:
      return 'text/plain';
  }
}

export function documentPreviewKindLabel(kind: DocumentPreviewKind): string {
  switch (kind) {
    case 'docx': return 'Word 文档';
    case 'pptx': return 'PowerPoint';
    case 'ppt': return '旧版 PowerPoint';
    case 'image': return '图片';
    case 'markdown': return 'Markdown';
    case 'json': return 'JSON';
    case 'html': return 'HTML';
    case 'pdf': return 'PDF';
    case 'text': return '文本';
    default: return '文件';
  }
}

export function formatFileSize(value?: number): string {
  if (!value || value <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

/**
 * 刚上传的附件 URL 在后端重新签发前可能暂时不可读取。
 * 这里在内存中保留原始 File/Blob，让发送前的附件也能立即本地预览。
 */
export function rememberDocumentSource(url: string, source: Blob): void {
  if (!url) return;
  localSources.delete(url);
  localSources.set(url, source);
  while (localSources.size > LOCAL_SOURCE_LIMIT) {
    const oldest = localSources.keys().next().value;
    if (oldest == null) break;
    localSources.delete(oldest);
  }
}

export async function loadDocumentSource(url: string): Promise<Blob> {
  const local = localSources.get(url);
  if (local) return local;
  const response = await authorizedFetch(assetUrl(url), { credentials: 'include' });
  if (!response.ok) {
    throw new Error(response.status === 403
      ? '预览签名尚未准备好，请发送消息后从对话附件中打开'
      : `文件读取失败（HTTP ${response.status}）`);
  }
  return response.blob();
}

export function formatDocumentText(content: string, kind: DocumentPreviewKind): string {
  const normalized = content.replace(/\r\n?/g, '\n');
  if (kind !== 'json') return normalized;
  const lines = normalized
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  if (lines.length <= 1) {
    try {
      return JSON.stringify(JSON.parse(normalized), null, 2);
    } catch {
      return normalized;
    }
  }
  return lines.map((line) => {
    try {
      return JSON.stringify(JSON.parse(line));
    } catch {
      return line;
    }
  }).join('\n');
}
