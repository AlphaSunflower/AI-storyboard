/**
 * 生成节点（生图 / 生视频）合并后的模式契约。
 *
 * 画布上只剩两个生成节点：`imageGeneration`（图片生成）与 `videoGeneration`（视频生成）。
 * 旧的四类视频节点、三类图片节点通过 {@link GENERATION_KIND_ALIASES} 归一到这两个 kind，
 * 真实差异改用节点数据里的 `mode` 表达。所有渲染、连线校验、执行入口都必须走本模块的
 * `resolveNodeMode` / `generationModeInputRule` / `resolveActionId`，避免出现第二套判断。
 */

export const VIDEO_GENERATION_KIND = 'videoGeneration' as const;
export const IMAGE_GENERATION_KIND = 'imageGeneration' as const;
export type MergedGenerationKind = typeof VIDEO_GENERATION_KIND | typeof IMAGE_GENERATION_KIND;

export const VIDEO_GENERATION_MODES = [
  { id: 'text2video', label: '文生视频' },
  { id: 'fullReference', label: '全能参考' },
  { id: 'firstLastFrame', label: '首尾帧' },
  { id: 'videoReference', label: '视频参考' },
] as const;

export const IMAGE_GENERATION_MODES = [
  { id: 'text2image', label: '文生图' },
  { id: 'fullReference', label: '全能参考' },
  { id: 'image2image', label: '图生图' },
  { id: 'batchRefine', label: '批量完善' },
] as const;

export type VideoGenerationMode = (typeof VIDEO_GENERATION_MODES)[number]['id'];
export type ImageGenerationMode = (typeof IMAGE_GENERATION_MODES)[number]['id'];
export type GenerationMode = VideoGenerationMode | ImageGenerationMode;

export const DEFAULT_VIDEO_GENERATION_MODE: VideoGenerationMode = 'text2video';
export const DEFAULT_IMAGE_GENERATION_MODE: ImageGenerationMode = 'text2image';

/** 旧生成节点 kind → 合并后的 canonical kind。 */
export const GENERATION_KIND_ALIASES: Record<string, MergedGenerationKind> = {
  textToImage: IMAGE_GENERATION_KIND,
  imageToImage: IMAGE_GENERATION_KIND,
  batchImageRefine: IMAGE_GENERATION_KIND,
  imageGen: IMAGE_GENERATION_KIND,
  textToVideo: VIDEO_GENERATION_KIND,
  imageToVideo: VIDEO_GENERATION_KIND,
  firstLastFrameToVideo: VIDEO_GENERATION_KIND,
  referenceToVideo: VIDEO_GENERATION_KIND,
  videoGen: VIDEO_GENERATION_KIND,
};

/** 已被合并、不再出现在节点库里的旧 kind（迁移会重写成 canonical kind）。 */
export const MERGED_GENERATION_SOURCE_KINDS: ReadonlySet<string> = new Set(Object.keys(GENERATION_KIND_ALIASES));

/**
 * 旧节点端口 id → 合并后端口 id。合并后的引用端口叫 `reference`；
 * 旧的首帧/尾帧/源图/多图端口都归到它（首帧/尾帧的顺序由 portOrder 承担）。
 */
export const LEGACY_GENERATION_PORT_ALIASES: Record<string, string> = {
  first: 'reference',
  last: 'reference',
  source: 'reference',
  reference: 'reference',
  images: 'reference',
};

/** 首尾帧模式里旧端口自带的顺序：首帧 0、尾帧 1。 */
export const LEGACY_GENERATION_PORT_ORDER: Record<string, number> = {
  first: 0,
  last: 1,
};

const VIDEO_MODE_BY_LEGACY_KIND: Record<string, VideoGenerationMode> = {
  textToVideo: 'text2video',
  imageToVideo: 'fullReference',
  firstLastFrameToVideo: 'firstLastFrame',
  referenceToVideo: 'fullReference',
};

const VIDEO_MODE_BY_DECLARED: Record<string, VideoGenerationMode> = {
  text2video: 'text2video',
  image2video: 'fullReference',
  fullReference: 'fullReference',
  videoReference: 'videoReference',
  firstLastFrame: 'firstLastFrame',
};

const IMAGE_MODE_BY_LEGACY_KIND: Record<string, ImageGenerationMode> = {
  textToImage: 'text2image',
  imageGen: 'text2image',
  imageToImage: 'image2image',
  batchImageRefine: 'batchRefine',
};

const VIDEO_MODE_IDS = new Set<string>(VIDEO_GENERATION_MODES.map((mode) => mode.id));
const IMAGE_MODE_IDS = new Set<string>(IMAGE_GENERATION_MODES.map((mode) => mode.id));

/** 把一个 kind（含旧 kind）归一为合并后的生成节点 kind；非生成节点返回 undefined。 */
export function mergedGenerationKind(kind: string | undefined): MergedGenerationKind | undefined {
  if (!kind) return undefined;
  if (kind === VIDEO_GENERATION_KIND || kind === IMAGE_GENERATION_KIND) return kind;
  return GENERATION_KIND_ALIASES[kind];
}

export function generationModesForKind(kind: string | undefined): readonly { id: string; label: string }[] {
  const merged = mergedGenerationKind(kind);
  if (merged === VIDEO_GENERATION_KIND) return VIDEO_GENERATION_MODES;
  if (merged === IMAGE_GENERATION_KIND) return IMAGE_GENERATION_MODES;
  return [];
}

export function defaultGenerationMode(kind: string | undefined): GenerationMode | undefined {
  const merged = mergedGenerationKind(kind);
  if (merged === VIDEO_GENERATION_KIND) return DEFAULT_VIDEO_GENERATION_MODE;
  if (merged === IMAGE_GENERATION_KIND) return DEFAULT_IMAGE_GENERATION_MODE;
  return undefined;
}

/**
 * 解析节点当前模式：优先 `data.mode`，其次旧 `data.videoMode`，最后按旧 kind 推断。
 * 未声明字段的存量节点（未走迁移）也能得到正确模式。
 */
export function resolveNodeMode(kind: string | undefined, data?: Record<string, unknown> | null): GenerationMode | undefined {
  const merged = mergedGenerationKind(kind);
  if (!merged) return undefined;
  const declared = typeof data?.mode === 'string' ? data.mode : undefined;
  if (merged === VIDEO_GENERATION_KIND) {
    if (declared && VIDEO_MODE_BY_DECLARED[declared]) return VIDEO_MODE_BY_DECLARED[declared];
    const legacyMode = typeof data?.videoMode === 'string' ? data.videoMode : undefined;
    if (legacyMode && VIDEO_MODE_BY_DECLARED[legacyMode]) return VIDEO_MODE_BY_DECLARED[legacyMode];
    return VIDEO_MODE_BY_LEGACY_KIND[kind ?? ''] ?? DEFAULT_VIDEO_GENERATION_MODE;
  }
  if (declared && IMAGE_MODE_IDS.has(declared)) return declared as ImageGenerationMode;
  if (declared === 'text2video') return DEFAULT_IMAGE_GENERATION_MODE;
  return IMAGE_MODE_BY_LEGACY_KIND[kind ?? ''] ?? DEFAULT_IMAGE_GENERATION_MODE;
}

export function isGenerationMode(value: unknown, kind: string | undefined): value is GenerationMode {
  if (typeof value !== 'string') return false;
  const merged = mergedGenerationKind(kind);
  if (merged === VIDEO_GENERATION_KIND) return VIDEO_MODE_IDS.has(value);
  if (merged === IMAGE_GENERATION_KIND) return IMAGE_MODE_IDS.has(value);
  return false;
}

export function generationModeLabel(kind: string | undefined, mode: string | undefined): string {
  return generationModesForKind(kind).find((entry) => entry.id === mode)?.label ?? '';
}

/** 某个模式对引用素材的要求（min/max 用于必填校验与「＋」上限）。 */
export interface GenerationModeInputRule {
  referenceMin: number;
  referenceMax: number;
  videoMin: number;
  videoMax: number;
}

const VIDEO_MODE_INPUT_RULES: Record<VideoGenerationMode, GenerationModeInputRule> = {
  text2video: { referenceMin: 0, referenceMax: 9, videoMin: 0, videoMax: 0 },
  fullReference: { referenceMin: 0, referenceMax: 9, videoMin: 0, videoMax: 1 },
  firstLastFrame: { referenceMin: 2, referenceMax: 2, videoMin: 0, videoMax: 0 },
  videoReference: { referenceMin: 0, referenceMax: 9, videoMin: 1, videoMax: 1 },
};

const IMAGE_MODE_INPUT_RULES: Record<ImageGenerationMode, GenerationModeInputRule> = {
  text2image: { referenceMin: 0, referenceMax: 14, videoMin: 0, videoMax: 0 },
  fullReference: { referenceMin: 0, referenceMax: 14, videoMin: 0, videoMax: 0 },
  image2image: { referenceMin: 1, referenceMax: 14, videoMin: 0, videoMax: 0 },
  batchRefine: { referenceMin: 1, referenceMax: 14, videoMin: 0, videoMax: 0 },
};

export function generationModeInputRule(
  kind: string | undefined,
  data?: Record<string, unknown> | null,
): GenerationModeInputRule | undefined {
  const merged = mergedGenerationKind(kind);
  if (!merged) return undefined;
  const mode = resolveNodeMode(kind, data);
  if (!mode) return undefined;
  const table: Record<string, GenerationModeInputRule> = merged === VIDEO_GENERATION_KIND
    ? VIDEO_MODE_INPUT_RULES
    : IMAGE_MODE_INPUT_RULES;
  return table[mode];
}

/** 模式对应的可执行动作 id（合并后的图片节点在 generate / redraw 之间切换）。 */
export function resolveActionId(kind: string | undefined, data?: Record<string, unknown> | null): string | undefined {
  const merged = mergedGenerationKind(kind);
  if (!merged) return undefined;
  if (merged === VIDEO_GENERATION_KIND) return 'generate';
  const mode = resolveNodeMode(kind, data);
  return mode === 'image2image' || mode === 'batchRefine' ? 'redraw' : 'generate';
}

/** 生成结果媒体类型（结果槽/占位契约共用）。 */
export function generationResultMedia(kind: string | undefined): 'image' | 'video' | undefined {
  const merged = mergedGenerationKind(kind);
  if (merged === VIDEO_GENERATION_KIND) return 'video';
  if (merged === IMAGE_GENERATION_KIND) return 'image';
  return undefined;
}
