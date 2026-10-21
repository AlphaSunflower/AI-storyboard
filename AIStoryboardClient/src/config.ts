/**
 * 前端共享配置。
 *
 * BACKEND_URL — 后端基础地址。
 *   开发环境：Vite 代理将 /api 转发到 localhost:8080，设为空字符串。
 *             （配置在 .env.development + vite.config.ts server.proxy）
 *   生产环境：前后端同域部署，设为空字符串（相对路径）。
 *   可通过 VITE_API_BASE_URL 环境变量覆盖（见 .env.development / .env.production）。
 *
 * assetUrl() — 将后端返回的相对路径拼接为完整 URL。
 */

export const BACKEND_URL: string =
  (import.meta as { env?: Record<string, string> }).env?.VITE_API_BASE_URL ?? 'http://localhost:8080';

// ── 轮询 / 超时配置（可通过 VITE_* 环境变量覆盖）──
/** Axios 全局请求超时（毫秒） */
export const API_TIMEOUT: number =
  Number((import.meta as { env?: Record<string, string> }).env?.VITE_API_TIMEOUT) || 120_000;
/** 视频任务轮询间隔（毫秒） */
export const VIDEO_POLL_INTERVAL: number =
  Number((import.meta as { env?: Record<string, string> }).env?.VITE_VIDEO_POLL_INTERVAL) || 5000;
/** 图片状态恢复轮询间隔（毫秒） */
export const IMAGE_POLL_INTERVAL: number =
  Number((import.meta as { env?: Record<string, string> }).env?.VITE_IMAGE_POLL_INTERVAL) || 3000;

/**
 * Resolve an asset path (image, video) to a full URL.
 */
export function assetUrl(path: string | null): string {
  if (!path) return '';
  // 已解析的 URL 与浏览器内 Blob URL 原样返回
  if (path.startsWith('http') || path.startsWith('data:') || path.startsWith('blob:')) return path;
  return BACKEND_URL + path;
}

/**
 * 聊天里的生成图走缩略图：`/api/files/images/**` 追加 `?w=`，后端按需缩放并落盘缓存。
 *
 * <p>生成图原图约 1 MB，聊天气泡里只按 220px 高显示；直出原图既费带宽，又要解码整张位图。
 * 保留原有 query（`token` 签名只覆盖路径，加参数不影响校验）。
 * 非 `/api/files/images/**`（外链、项目库 media、data URI）原样返回。
 */
export function imageThumbnailUrl(url: string | null, width = 480): string {
  if (!url) return '';
  if (!url.includes('/api/files/images/')) return url;
  if (/[?&]w=\d+/.test(url)) return url;
  return `${url}${url.includes('?') ? '&' : '?'}w=${width}`;
}

// ═══════════════════════════════════════════════════
//  Model definitions — single source of truth.
//  Add/remove models here; all <select> pickers
//  read from these arrays.
//  运行时优先使用网关下发的模型列表（见 projectStore.fetchAiModels），
//  这些静态数组作为网关不可用/未配置路由时的兜底默认值。
// ═══════════════════════════════════════════════════

/** 图片模型参数能力（images/generations + edits 共用；来自网关 model_params 配置） */
export interface ImageModelParams {
  n?: { min?: number; max?: number; default?: number };
  sizes?: string[];
  sizeDefault?: string;
  qualities?: string[];
  qualityDefault?: string;
  styles?: string[];
  styleDefault?: string;
  /** 可选比例（网关 model_params.aspect_ratios；未配置时前端只提供「智能」） */
  aspectRatios?: string[];
  aspectRatioDefault?: string;
}

/** 视频模型参数能力（videos 契约） */
export interface VideoModelParams {
  durations?: number[];
  durationDefault?: number;
  resolutions?: string[];
  resolutionDefault?: string;
  aspectRatios?: string[];
  aspectRatioDefault?: string;
}

/** 文本模型参数默认值（chat/completions） */
export interface TextModelParams {
  defaults?: { temperature?: number; max_tokens?: number; top_p?: number };
}

/** 理解模型（vision 类型）参数能力：上传参考图约束（来自网关 model_params refImages/maxImageSizeMB） */
export interface UnderstandingModelParams {
  refImages?: { min?: number; max?: number };
  maxImageSizeMB?: number;
}

/** 模型下拉选项（网关下发与静态默认统一结构；params 为解析后的参数对象，未配置时为 null/undefined） */
export interface ModelOption {
  value: string;
  label: string;
  /** 网关下发的是否为默认模型（is_default → "true"；布尔兼容）。 */
  default?: string | boolean;
  params?: ImageModelParams | VideoModelParams | TextModelParams | UnderstandingModelParams | null;
  /** 后端 /ai/models 归一化的生成偏好能力（比例选项 + 清晰度标签/取值键）；缺失时前端本地推导 */
  preferences?: {
    ratioOptions?: string[];
    ratioDefault?: string;
    clarity?: { label?: string; param?: string; options?: string[]; default?: string } | null;
  } | null;
}

export const IMAGE_MODELS = [
  { value: 'doubao-seedream-4-0-250828', label: 'Doubao Seedream 4.0' },
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

export const UNDERSTANDING_MODELS = [
  { value: 'gemini-3-flash-preview', label: 'Gemini 3 Flash' },
] as const;

export const DEFAULT_IMAGE_MODEL: string = IMAGE_MODELS[0].value;
export const DEFAULT_VIDEO_MODEL: string = VIDEO_MODELS[0].value;
export const DEFAULT_UNDERSTANDING_MODEL: string = UNDERSTANDING_MODELS[0].value;

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
  // 注意：分辨率默认档统一 768P（MiniMax 仅支持 768P/2K，720p 会 400）；无分镜覆盖时前端不再下发
  // resolution，由后端 config 默认档兜底；真实生效参数 = duration + aspectRatio（MiniMax ratio 白名单
  // 21:9/16:9/4:3/1:1/3:4/9:16；官方 duration 合法范围 4~15 整数，此处 UI 提供常用 4/6/8 秒档）
  { value: '4s-16:9',  label: '4秒 横屏',   seconds: '4', duration: '4',  size: '1280x720',  resolution: '768P', aspectRatio: '16:9' },
  { value: '6s-16:9',  label: '6秒 横屏',   seconds: '6', duration: '6',  size: '1280x720',  resolution: '768P', aspectRatio: '16:9' },
  { value: '8s-16:9',  label: '8秒 横屏',   seconds: '8', duration: '8',  size: '1280x720',  resolution: '768P', aspectRatio: '16:9' },
  { value: '4s-9:16',  label: '4秒 竖屏',   seconds: '4', duration: '4',  size: '720x1280',  resolution: '768P', aspectRatio: '9:16' },
  { value: '6s-9:16',  label: '6秒 竖屏',   seconds: '6', duration: '6',  size: '720x1280',  resolution: '768P', aspectRatio: '9:16' },
  { value: '8s-9:16',  label: '8秒 竖屏',   seconds: '8', duration: '8',  size: '720x1280',  resolution: '768P', aspectRatio: '9:16' },
];

export const DEFAULT_VIDEO_PRESET: string = VIDEO_PRESETS[2].value; // 8秒 横屏（与官方/原系统默认一致）

// ═══════════════════════════════════════════════════
//  Image generation: size + quality
// ═══════════════════════════════════════════════════

export const IMAGE_SIZES = [
  '1K',
  '2K',
  '4K',
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

/** Seedream 4.x 使用 Ark 原生生图协议，不支持 quality。 */
export function isSeedreamImageModel(model?: string | null): boolean {
  return !!model && model.startsWith('doubao-seedream-');
}

// ═══════════════════════════════════════════════════
//  Reference asset upload limits (static fallback)
//  网关 params 精确值优先（refImages/refVideos/audioCount/max*SizeMB），
//  未配置时前端用此兜底展示上传约束。
// ═══════════════════════════════════════════════════

export const REFERENCE_LIMITS = {
  image: { maxCount: 10, maxSizeMB: 30 },
  video: { maxCount: 3, maxSizeMB: 50 },
  audio: { maxCount: 3, maxSizeMB: 15 },
} as const;
