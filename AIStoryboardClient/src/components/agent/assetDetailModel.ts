import type { AgentAsset } from '../../api/agent';
import { parseParams } from '../../stores/generationRecordsStore';
import { assetUrls, fileNameFromAssetUrl } from './assetBrowserModel';

/**
 * 会话资产详情面板的纯数据层。
 *
 * 详情右侧要回答的是「这张图是怎么来的」：提示词、模型、画幅、分辨率、耗时、时间。
 * 这些字段都落在 `agent_assets` 行上（prompt / model / params / durationMs / createdAt），
 * 但 `params` 是一段 JSON 字符串、字段随生成类型而变，所以拼装逻辑集中在这里，
 * 组件只负责排版，测试只测这一层。
 */

export type AssetMediaKind = 'image' | 'video' | 'document';

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|avif|svg|bmp)(?:[?#]|$)/i;
const VIDEO_EXT = /\.(mp4|webm|mov|m4v|avi|mkv)(?:[?#]|$)/i;

/** 生成类型的中文名，与生成记录弹窗同一套口径。 */
export const ASSET_GEN_TYPE_LABEL: Record<string, string> = {
  text_to_image: '文生图',
  image_to_image: '图生图',
  text_to_video: '文生视频',
  ref_to_video: '参考生视频',
  multi_image_to_image: '多图生图',
  multi_image_to_multi_image: '多图生多图',
  first_frame_to_video: '首帧生视频',
  first_last_frame_to_video: '首尾帧生视频',
  storyboard_video: '分镜视频',
  storyboard_image: '分镜图片',
  sandbox_generated: '沙箱文件',
};

export function assetGenTypeLabel(genType: string | null | undefined, type?: string | null): string {
  if (genType && ASSET_GEN_TYPE_LABEL[genType]) return ASSET_GEN_TYPE_LABEL[genType];
  if (type === 'video') return '视频';
  if (type === 'image') return '图片';
  if (type === 'document') return '文档';
  return '素材';
}

/**
 * 详情里怎么放这张素材：先信资产类型，类型说不清（reference / other）再看扩展名，
 * 都不像就按文档处理。会话资产里上传的参考图常被标成 reference，靠扩展名才认得出是图片。
 */
export function assetMediaKind(
  type: AgentAsset['type'] | string | null | undefined,
  url: string,
): AssetMediaKind {
  if (type === 'image') return 'image';
  if (type === 'video') return 'video';
  if (type === 'document') return 'document';
  if (VIDEO_EXT.test(url)) return 'video';
  if (IMAGE_EXT.test(url)) return 'image';
  return 'document';
}

/** 参数键的中文名。后端写入的键见 MoonAgent 的 buildImageParams / buildVideoParams。 */
export const ASSET_PARAM_LABELS: Record<string, string> = {
  model: '模型',
  aspectRatio: '画幅',
  resolution: '分辨率',
  size: '尺寸',
  duration: '时长',
  quality: '质量',
  n: '数量',
  mode: '模式',
  negativePrompt: '负向提示词',
};

/** 参数区固定顺序：先回答「用什么模型、什么比例」，再是尺寸、时长这类细节。 */
const PARAM_ORDER = [
  'model', 'aspectRatio', 'resolution', 'size', 'duration', 'quality', 'n', 'mode', 'negativePrompt',
];

export interface AssetParamEntry {
  key: string;
  label: string;
  value: string;
}

function greatestCommonDivisor(a: number, b: number): number {
  let left = a;
  let right = b;
  while (right !== 0) {
    const next = left % right;
    left = right;
    right = next;
  }
  return left;
}

/**
 * 从尺寸串推画幅比：`1536x1024` → `3:2`。
 *
 * 后端只在视频参数里存 aspectRatio，图片常常只有 size，但详情要回答「什么比例」，
 * 所以按尺寸现算。约分后出现 1023:1025 这种数字说明它不是常见画幅，宁可不猜。
 */
export function aspectRatioFromSize(size: string | null | undefined): string | null {
  const match = /^\s*(\d{2,5})\s*[x×]\s*(\d{2,5})\s*$/i.exec(size ?? '');
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (!width || !height) return null;
  const divisor = greatestCommonDivisor(width, height);
  const ratioWidth = width / divisor;
  const ratioHeight = height / divisor;
  if (ratioWidth > 64 || ratioHeight > 64) return null;
  return `${ratioWidth}:${ratioHeight}`;
}

function stringifyParamValue(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value.trim() ? value.trim() : null;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) {
    const parts = value.map((item) => stringifyParamValue(item)).filter(Boolean);
    return parts.length ? parts.join('、') : null;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return null;
  }
}

/**
 * 参数条目：模型 → 画幅 → 分辨率 → 尺寸 → 时长 → 其余后端键。
 *
 * `model` 既可能落在资产列上也可能落在 params 里，两处都读；画幅在只有 size 时现算，
 * 免得详情里「画幅」一栏永远空着。
 */
export function buildAssetParamEntries(asset: AgentAsset): AssetParamEntry[] {
  const params = parseParams(asset.params ?? null);
  const values = new Map<string, string>();

  for (const [key, raw] of Object.entries(params)) {
    const value = stringifyParamValue(raw);
    if (value !== null) values.set(key, value);
  }
  const model = (asset.model ?? '').trim() || values.get('model') || '';
  if (model) values.set('model', model);

  if (!values.has('aspectRatio')) {
    const ratio = aspectRatioFromSize(values.get('size'));
    if (ratio) values.set('aspectRatio', ratio);
  }

  const ordered: AssetParamEntry[] = [];
  for (const key of PARAM_ORDER) {
    const value = values.get(key);
    if (value === undefined) continue;
    ordered.push({ key, label: ASSET_PARAM_LABELS[key] ?? key, value });
    values.delete(key);
  }
  // 后端将来加字段时不要静默丢掉：没进表的名字原样展示。
  for (const [key, value] of values) {
    ordered.push({ key, label: ASSET_PARAM_LABELS[key] ?? key, value });
  }
  return ordered;
}

/**
 * 素材身份条目：花了多久、什么时候出的。
 *
 * 生成方式刻意不在这里重复一遍 —— 它已经是详情标题本身，同一块面板里写两遍只是噪音。
 */
export function buildAssetMetaEntries(asset: AgentAsset): AssetParamEntry[] {
  return [
    { key: 'durationMs', label: '耗时', value: formatAssetDuration(asset.durationMs) },
    { key: 'createdAt', label: '生成时间', value: formatAssetTime(asset.createdAt) },
  ];
}

/** 耗时口径与生成记录一致：60 秒以内说秒，超过说「X 分 Y 秒」，整分不留「0 秒」。 */
export function formatAssetDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '未记录';
  const seconds = Math.max(0, Math.round(ms / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
}

/** 生成时间：今天 / 昨天 / 月-日 / 年-月-日 + 时分。 */
export function formatAssetTime(iso: string | null | undefined): string {
  if (!iso) return '未记录';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  if (sameDay) return `今天 ${clock}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const isYesterday = date.getFullYear() === yesterday.getFullYear()
    && date.getMonth() === yesterday.getMonth()
    && date.getDate() === yesterday.getDate();
  if (isYesterday) return `昨天 ${clock}`;
  const monthDay = `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  if (date.getFullYear() === now.getFullYear()) return `${monthDay} ${clock}`;
  return `${date.getFullYear()}-${monthDay} ${clock}`;
}

/** 详情舞台上的结果列表：多图记录展开成多张，单图记录就是这一张。 */
export function assetResultUrls(asset: AgentAsset): string[] {
  return assetUrls(asset);
}

/**
 * 详情标题：媒体说清「这是什么生成」，文档只能报文件名。
 * 模型名刻意不重复进标题，它在参数区里作为独立一栏出现。
 */
export function assetDetailTitle(asset: AgentAsset, url: string): string {
  if (assetMediaKind(asset.type, url) === 'document') return fileNameFromAssetUrl(url);
  return assetGenTypeLabel(asset.genType, asset.type);
}
