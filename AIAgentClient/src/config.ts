/**
 * 前端共享配置。
 * BACKEND_URL — 开发环境由 Vite 代理 /api -> localhost:8082，设空字符串。
 * assetUrl()  — 将后端返回的相对路径拼接为完整 URL。
 */

export const BACKEND_URL: string = '';

/** Resolve an asset path (image, video) to a full URL. */
export function assetUrl(path: string | null): string {
  if (!path) return '';
  if (path.startsWith('http') || path.startsWith('data:')) return path;
  return BACKEND_URL + path;
}
