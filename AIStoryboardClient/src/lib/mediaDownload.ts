import { notify } from '../stores/toastStore';

/** 从 URL / mime 推断落盘后缀 */
export function imageExtension(url: string, mime: string): string {
  if (mime.includes('png')) return 'png';
  if (mime.includes('webp')) return 'webp';
  if (mime.includes('gif')) return 'gif';
  if (mime.includes('jpeg') || mime.includes('jpg')) return 'jpg';
  if (mime.includes('webm')) return 'webm';
  if (mime.includes('mp4')) return 'mp4';
  const matched = url.match(/\.(png|jpe?g|gif|webp|mp4|webm)(?:[?#]|$)/i)?.[1];
  return matched?.toLowerCase().replace('jpeg', 'jpg') ?? 'png';
}

export function triggerMediaDownload(href: string, filename: string, openInNewTab = false): void {
  const link = document.createElement('a');
  link.href = href;
  link.download = filename;
  link.rel = 'noreferrer';
  if (openInNewTab) link.target = '_blank';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

/**
 * 图片/视频下载：优先取回 blob 再落盘（跨源链接拿不到 download 文件名），
 * 取回失败时退回让浏览器直接打开原始地址。
 *
 * 文件名基名先去掉自带后缀：调用方常常直接把 `xxx.png` 传进来，
 * 不去掉就会落成 `xxx.png.png`。
 */
export async function downloadMedia(url: string, alt?: string): Promise<void> {
  const filenameBase = (alt?.trim() || 'chat-media')
    .replace(/\.[a-z0-9]{1,8}$/i, '')
    .replace(/[\\/:*?"<>|]+/g, '-')
    .replace(/\s+/g, '-');
  let filename = `${filenameBase || 'chat-media'}.${imageExtension(url, '')}`;
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    filename = `${filenameBase || 'chat-media'}.${imageExtension(url, blob.type)}`;
    triggerMediaDownload(objectUrl, filename);
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  } catch {
    triggerMediaDownload(url, filename, true);
  }
  notify({ title: '已开始下载', description: filename, variant: 'info' });
}
