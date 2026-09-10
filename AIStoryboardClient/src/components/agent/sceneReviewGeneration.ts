export interface SceneImageGenerationConfig {
  imageModel?: string | null;
  imageSize?: string | null;
  imageQuality?: string | null;
  imageN?: number | null;
}

export interface SceneImageSubmitParams extends Record<string, string> {
  sceneImageParams: string;
}

/**
 * 审核列表按分镜组装独立生成参数。
 * 行内修改优先，其次使用分镜已存配置，最后回退网关默认模型/参数。
 */
export function buildSceneImageSubmitParams(
  selectedIds: string[],
  sceneConfigs: Record<string, SceneImageGenerationConfig | undefined>,
  rowOverrides: Record<string, Record<string, string> | undefined>,
  defaultModel?: string | null,
): SceneImageSubmitParams {
  const perScene: Record<string, { model?: string; size?: string; quality?: string; n: number }> = {};
  for (const sceneId of selectedIds) {
    const config = sceneConfigs[sceneId];
    const override = rowOverrides[sceneId] ?? {};
    const model = override.imageModel || config?.imageModel || defaultModel || undefined;
    const size = override.imageSize || config?.imageSize || undefined;
    const quality = override.imageQuality || config?.imageQuality || undefined;
    const parsedN = Number(override.imageN ?? config?.imageN ?? 1);
    const n = Number.isFinite(parsedN) && parsedN > 0 ? Math.floor(parsedN) : 1;
    perScene[sceneId] = {
      ...(model ? { model } : {}),
      ...(size ? { size } : {}),
      ...(quality ? { quality } : {}),
      n,
    };
  }
  return { sceneImageParams: JSON.stringify(perScene) };
}
