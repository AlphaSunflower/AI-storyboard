export const IMAGE_GEN_TYPES = [
  {
    key: 'portrait',
    label: '参考物四视图',
    prompt: '纯白或简洁背景，以参考主体为准生成四视图。从左到右依次生成【正面视图、侧面视图、背面视图、细节视图】，生成在一张图上。四格保持主体外形、材质、结构和风格完全一致。',
  },
  {
    key: 'storyboard',
    label: '故事板',
    prompt: '故事板提示：创建一个电影制作板/视觉规划表，比例16:9，展示短片或商业广告的完整。包含清晰的镜头编号、镜头大小、运动方式、动作和情绪进展。',
  },
  {
    key: 'multi-grid',
    label: '多宫格',
    prompt: '多宫格摄影作品集：以至少两张参考图建立一致性锚点，再生成 3x5 摄影预览网格图（15个不同视角分镜），主体特征、场景、环境和光影完全一致。',
  },
  { key: 'custom', label: '普通', prompt: '' },
  {
    key: 'expand',
    label: '扩图',
    prompt: '扩图提示：以参考图主体和构图为基础，向四周扩展画面并补全边缘场景、背景与细节，保持主体、透视、光影、材质和整体风格一致，不改变主体核心特征。',
  },
] as const;

export type ImagePresetKey = typeof IMAGE_GEN_TYPES[number]['key'];
export const DEFAULT_IMAGE_PRESET_KEY: ImagePresetKey = 'custom';

export function validateImagePresetReferences(presetKey: string | undefined, referenceCount: number): string | null {
  const minimum = presetKey === 'multi-grid' ? 2 : 0;
  return referenceCount < minimum ? `多宫格至少需要 ${minimum} 张参考图` : null;
}

export function buildImagePresetPrompt(presetKey: string | undefined, prompt: string): string {
  const preset = IMAGE_GEN_TYPES.find((item) => item.key === presetKey);
  const text = prompt.trim();
  if (!preset?.prompt) return text;
  return text ? `${text}\n\n${preset.prompt}` : preset.prompt;
}
