import { useEffect, useState } from 'react';
import { apiJson } from '../services/morrowApi';

/** 网关模型项：来自 GET /models?type=X 的 data[]，params 为能力枚举 JSON 字符串。 */
export interface GatewayModel {
  value: string;
  label: string;
  default?: 'true';
  params?: string;
}

export interface ModelParamSpecField {
  key: string;
  label?: string;
  type?: 'string' | 'number' | 'range' | 'bool' | 'boolean' | string;
  min?: number;
  max?: number;
  default?: unknown;
  show?: boolean;
}

/** 解析后的模型能力。 */
export interface ModelParams {
  sizes?: string[];
  aspectRatios?: string[];
  resolutions?: string[];
  durations?: number[];
  qualities?: string[];
  styles?: string[];
  sizeDefault?: string;
  qualityDefault?: string;
  styleDefault?: string;
  aspectRatioDefault?: string;
  n?: { min?: number; max?: number; default?: number };
  refImages?: { min?: number; max?: number };
  maxPromptChars?: number;
  spec?: {
    modes?: unknown[];
    promptKey?: string;
    request?: ModelParamSpecField[];
    [k: string]: unknown;
  };
  [k: string]: unknown;
}

const DEFAULT_ALIASES: Record<string, string> = {
  hd: 'high',
  standard: 'medium',
};

/** 把网关历史默认值映射到当前能力枚举；未知值回退到第一个选项。 */
export function normalizeModelDefault(value: unknown, options: string[] = []): string | undefined {
  const raw = value === undefined || value === null ? '' : String(value);
  const candidate = DEFAULT_ALIASES[raw.toLowerCase()] ?? raw;
  if (candidate && options.includes(candidate)) return candidate;
  return options[0];
}

/** 解析网关 params JSON 字符串 → 能力枚举对象（失败返回空）。 */
export function parseModelParams(raw?: string): ModelParams {
  if (!raw) return {};
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>;
    const pickArr = (v: unknown): string[] | undefined =>
      Array.isArray(v)
        ? v.map(String)
        : typeof v === 'string' && v.trim()
          ? v.split(/[,\s]+/).map((item) => item.trim()).filter(Boolean)
          : undefined;
    const pickNumber = (v: unknown): number | undefined => {
      const parsed = Number(v);
      return Number.isFinite(parsed) ? parsed : undefined;
    };
    const n = obj.n && typeof obj.n === 'object'
      ? {
          min: pickNumber((obj.n as Record<string, unknown>).min),
          max: pickNumber((obj.n as Record<string, unknown>).max),
          default: pickNumber((obj.n as Record<string, unknown>).default),
        }
      : undefined;
    const refImages = obj.refImages && typeof obj.refImages === 'object'
      ? {
          min: pickNumber((obj.refImages as Record<string, unknown>).min),
          max: pickNumber((obj.refImages as Record<string, unknown>).max),
        }
      : undefined;
    return {
      ...obj,
      sizes: pickArr(obj.sizes ?? obj.size ?? obj.supportedSizes),
      aspectRatios: pickArr(obj.aspectRatios ?? obj.aspectRatio ?? obj.ratios),
      resolutions: pickArr(obj.resolutions ?? obj.resolution),
      durations: Array.isArray(obj.durations) ? obj.durations.map(Number) : undefined,
      qualities: pickArr(obj.qualities ?? obj.quality),
      styles: pickArr(obj.styles ?? obj.style),
      sizeDefault: typeof obj.sizeDefault === 'string' ? obj.sizeDefault : undefined,
      qualityDefault: typeof obj.qualityDefault === 'string' ? obj.qualityDefault : undefined,
      styleDefault: typeof obj.styleDefault === 'string' ? obj.styleDefault : undefined,
      aspectRatioDefault: typeof obj.aspectRatioDefault === 'string' ? obj.aspectRatioDefault : undefined,
      n,
      refImages,
      maxPromptChars: pickNumber(obj.maxPromptChars),
      spec: obj.spec && typeof obj.spec === 'object'
        ? obj.spec as ModelParams['spec']
        : undefined,
    };
  } catch {
    return {};
  }
}

/**
 * 拉取网关真实模型列表（经后端 /api/canvas/models 代理，前端不碰网关 key）。
 * 返回 { models, defaultModel, loading }；网关不可达时 models 为空（前端回退本地硬编码）。
 */
export function useGatewayModels(type: 'image' | 'video') {
  const [models, setModels] = useState<GatewayModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    apiJson<GatewayModel[]>(`/models?type=${type}`)
      .then((list) => {
        if (!cancelled) setModels(Array.isArray(list) ? list : []);
      })
      .catch((caught) => {
        if (!cancelled) {
          setModels([]);
          setError(caught instanceof Error ? caught.message : '模型列表加载失败');
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [type]);

  const defaultModel = models.find((m) => m.default === 'true')?.value ?? models[0]?.value ?? '';
  return { models, defaultModel, loading, error };
}
