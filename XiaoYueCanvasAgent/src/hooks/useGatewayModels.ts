import { useEffect, useState } from 'react';
import { apiJson } from '../services/morrowApi';

/** 网关模型项：来自 GET /models?type=X 的 data[]，params 为能力枚举 JSON 字符串。 */
export interface GatewayModel {
  value: string;
  label: string;
  default?: 'true';
  params?: string;
}

/** 解析后的模型能力。 */
export interface ModelParams {
  sizes?: string[];
  aspectRatios?: string[];
  resolutions?: string[];
  durations?: number[];
  [k: string]: unknown;
}

/** 解析网关 params JSON 字符串 → 能力枚举对象（失败返回空）。 */
export function parseModelParams(raw?: string): ModelParams {
  if (!raw) return {};
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>;
    const pickArr = (v: unknown): string[] | undefined =>
      Array.isArray(v) ? v.map(String) : typeof v === 'string' && /[\\[,]/.test(v) ? v.split(/[\\s,]+/).filter(Boolean) : undefined;
    return {
      sizes: pickArr(obj.sizes ?? obj.size ?? obj.supportedSizes),
      aspectRatios: pickArr(obj.aspectRatios ?? obj.aspectRatio ?? obj.ratios),
      resolutions: pickArr(obj.resolutions ?? obj.resolution),
      durations: Array.isArray(obj.durations) ? obj.durations.map(Number) : undefined,
      ...obj,
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

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    apiJson<GatewayModel[]>(`/models?type=${type}`)
      .then((list) => { if (!cancelled) setModels(Array.isArray(list) ? list : []); })
      .catch(() => { if (!cancelled) setModels([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [type]);

  const defaultModel = models.find((m) => m.default === 'true')?.value ?? models[0]?.value ?? '';
  return { models, defaultModel, loading };
}
