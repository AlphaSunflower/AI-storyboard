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

// Dify 平台地址：工作流工具生成的文件（图片/视频）URL 是 /files/tools/ 相对路径，
// 需拼 Dify base 才能访问；默认与后端 DIFY_BASE_URL 一致（本机 80 端口）
export const DIFY_BASE_URL: string =
  (import.meta as { env?: Record<string, string> }).env?.VITE_DIFY_BASE_URL ?? 'http://localhost';

/**
 * Resolve an asset path (image, video) to a full URL.
 */
export function assetUrl(path: string | null): string {
  if (!path) return '';
  // http(s) 绝对 URL 与 data: URI 原样返回（data: 来自 Dify 内联 base64 图片）
  if (path.startsWith('http') || path.startsWith('data:')) return path;
  // Dify 工具文件（/files/tools/ 签名 URL）需拼 Dify 平台地址而非后端地址
  if (path.startsWith('/files/tools/')) return DIFY_BASE_URL + path;
  return BACKEND_URL + path;
}

// ═══════════════════════════════════════════════════
//  Model definitions — single source of truth.
//  Add/remove models here; all <select> pickers
//  read from these arrays.
//  运行时优先使用网关下发的模型列表（见 projectStore.fetchAiModels），
//  这些静态数组作为网关不可用/未配置路由时的兜底默认值。
// ═══════════════════════════════════════════════════

/** 模型下拉选项（网关下发与静态默认统一结构） */
export interface ModelOption {
  value: string;
  label: string;
}

export const IMAGE_MODELS = [
  { value: 'gpt-image-2', label: 'GPT Image 2' },
  { value: 'gemini-3-pro-image-preview', label: 'Gemini 3 Pro Image' },
  { value: 'dall-e-3', label: 'DALL·E 3' },
  { value: 'sdxl', label: 'Stable Diffusion XL' },
  { value: 'midjourney-v6', label: 'Midjourney V6' },
  { value: 'flux-pro', label: 'FLUX Pro' },
] as const;

export const VIDEO_MODELS = [
  { value: 'MiniMax-H3', label: 'MiniMax H3' },
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
  // 注意：分辨率由后端统一为配置默认档（768P），size/resolution 字段后端忽略，仅兼容保留；
  // 真实生效参数 = duration + aspectRatio（MiniMax ratio 白名单 21:9/16:9/4:3/1:1/3:4/9:16；
  // 官方 duration 合法范围 4~15 整数，此处 UI 提供常用 4/6/8 秒档）
  { value: '4s-16:9',  label: '4秒 横屏',   seconds: '4', duration: '4',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '6s-16:9',  label: '6秒 横屏',   seconds: '6', duration: '6',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '8s-16:9',  label: '8秒 横屏',   seconds: '8', duration: '8',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '4s-9:16',  label: '4秒 竖屏',   seconds: '4', duration: '4',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
  { value: '6s-9:16',  label: '6秒 竖屏',   seconds: '6', duration: '6',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
  { value: '8s-9:16',  label: '8秒 竖屏',   seconds: '8', duration: '8',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
];

export const DEFAULT_VIDEO_PRESET: string = VIDEO_PRESETS[2].value; // 8秒 横屏（与官方/原系统默认一致）

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
