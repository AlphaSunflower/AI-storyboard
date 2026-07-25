/**
 * 前端共享配置。
 *
 * BACKEND_URL — 后端基础地址。
 *   开发环境：Vite 代理将 /api 转发到 localhost:8082，设为空字符串。
 *             （配置在 .env.development + vite.config.ts server.proxy）
 *   生产环境：前后端同域部署，设为空字符串（相对路径）。
 *   可通过 VITE_API_BASE_URL 环境变量覆盖（见 .env.development / .env.production）。
 *
 * assetUrl() — 将后端返回的相对路径拼接为完整 URL。
 */

export const BACKEND_URL: string =
  (import.meta as { env?: Record<string, string> }).env?.VITE_API_BASE_URL ?? 'http://localhost:8082';

/**
 * Resolve an asset path (image, video) to a full URL.
 */
export function assetUrl(path: string | null): string {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  return BACKEND_URL + path;
}

// ═══════════════════════════════════════════════════
//  Model definitions — single source of truth.
//  Add/remove models here; all <select> pickers
//  read from these arrays.
// ═══════════════════════════════════════════════════

export const IMAGE_MODELS = [
  { value: 'gpt-image-2', label: 'GPT Image 2' },
  { value: 'gemini-3-pro-image-preview', label: 'Gemini 3 Pro Image' },
  { value: 'dall-e-3', label: 'DALL·E 3' },
  { value: 'sdxl', label: 'Stable Diffusion XL' },
  { value: 'midjourney-v6', label: 'Midjourney V6' },
  { value: 'flux-pro', label: 'FLUX Pro' },
] as const;

export const VIDEO_MODELS = [
  { value: 'veo-3.1-fast', label: 'Veo 3.1 Fast' },
  { value: 'veo-3.1', label: 'Veo 3.1' },
  { value: 'runway-gen3', label: 'Runway Gen-3' },
  { value: 'kling-2', label: 'Kling 2' },
  { value: 'sora', label: 'Sora' },
] as const;

export const DEFAULT_IMAGE_MODEL: string = IMAGE_MODELS[0].value;
export const DEFAULT_VIDEO_MODEL: string = VIDEO_MODELS[0].value;

// ═══════════════════════════════════════════════════
//  Video duration + resolution presets
// ═══════════════════════════════════════════════════

export interface VideoPreset {
  value: string;
  label: string;
  seconds: string;
  duration: string;
  size: string;
  resolution: string;
  aspectRatio: string;
}

export const VIDEO_PRESETS: VideoPreset[] = [
  { value: '4s-720p',    label: '4秒 横屏 720p',  seconds: '4', duration: '4', size: '1280x720',  resolution: '720p',  aspectRatio: '16:9' },
  { value: '8s-720p',    label: '8秒 横屏 720p',  seconds: '8', duration: '8', size: '1280x720',  resolution: '720p',  aspectRatio: '16:9' },
  { value: '8s-1080p',   label: '8秒 横屏 1080p', seconds: '8', duration: '8', size: '1920x1080', resolution: '1080p', aspectRatio: '16:9' },
  { value: '8s-1080p-v', label: '8秒 竖屏 1080p', seconds: '8', duration: '8', size: '1080x1920', resolution: '1080p', aspectRatio: '9:16' },
  { value: '8s-4k',      label: '8秒 横屏 4K',    seconds: '8', duration: '8', size: '3840x2160', resolution: '4k',    aspectRatio: '16:9' },
];

export const DEFAULT_VIDEO_PRESET: string = VIDEO_PRESETS[1].value; // 8s 720p

// ═══════════════════════════════════════════════════
//  Image generation: size + quality
// ═══════════════════════════════════════════════════

export const IMAGE_SIZES = [
  '1024x1024',
  '1536x1024',
  '1024x1536',
  '2048x2048',
  '2048x1152',
  '3840x2160',
  '2160x3840',
  'auto',
] as const;

export const IMAGE_QUALITIES = ['low', 'medium', 'high', 'auto'] as const;

export const DEFAULT_IMAGE_SIZE: string = '1024x1024';
export const DEFAULT_IMAGE_QUALITY: string = 'auto';
