export const MULTI_ANGLE_PREFERRED_MODELS = [
  'doubao-seedream-4-5-251128',
  'doubao-seedream-4-0-250828',
] as const;

export type MultiAngleMode = 'grid' | 'multi';

export interface MultiAngleRequest {
  multiAngleMode: MultiAngleMode;
  model: string;
  size: '2K';
  n: 1 | 4;
}

const PROMPTS: Record<MultiAngleMode, string> = {
  grid: '[参考图1] 以参考图中的主体为准，保持身份、外形、结构、材质、颜色、光影和背景一致。生成一张 2x2 四宫格多角度视图：左上正面，右上左侧45度，左下右侧45度，右下背面。四个视角清晰独立、不重复，不添加文字、编号、边框或水印。',
  multi: '[参考图1] 以参考图中的主体为准，保持身份、外形、结构、材质、颜色、光影和背景一致。生成4张独立的多角度视图，由你判断最能展示主体结构特征的4个不同角度。每张仅展示一个视角，视角差异明显、不重复，不添加文字、编号、边框或水印。',
};

export function selectMultiAngleModel(
  models: Array<{ value: string }>,
): string | null {
  const available = new Set(models.map((model) => model.value));
  return MULTI_ANGLE_PREFERRED_MODELS.find((model) => available.has(model)) ?? null;
}

export function buildMultiAnglePrompt(mode: MultiAngleMode): string {
  return PROMPTS[mode];
}

export function multiAngleRequest(mode: MultiAngleMode, model: string): MultiAngleRequest {
  return {
    multiAngleMode: mode,
    model,
    size: '2K',
    n: mode === 'grid' ? 1 : 4,
  };
}

export function multiAngleNodeData(sourceLabel: string, request: MultiAngleRequest) {
  return {
    label: `${sourceLabel} · 多角度`,
    prompt: buildMultiAnglePrompt(request.multiAngleMode),
    status: 'idle' as const,
    multiAngle: request,
    model: request.model,
    size: request.size,
    n: request.n,
  };
}

export function multiAngleLabel(mode: unknown): string | null {
  if (mode === 'grid') return '多角度宫格';
  if (mode === 'multi') return '多角度多图';
  return null;
}
