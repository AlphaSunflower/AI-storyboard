export interface SceneImageSource {
  imageUrl?: string | null;
  imageUrls?: string | string[] | null;
}

/** 统一提取分镜全部图片；imageUrls 优先，imageUrl 作为旧数据兜底。 */
export function getSceneImageUrls(scene: SceneImageSource | null | undefined): string[] {
  if (!scene) return [];
  const raw = Array.isArray(scene.imageUrls)
    ? scene.imageUrls
    : typeof scene.imageUrls === 'string'
      ? scene.imageUrls.split(',')
      : [];
  const urls = raw.map((url) => url.trim()).filter(Boolean);
  if (urls.length > 0) return [...new Set(urls)];
  return scene.imageUrl?.trim() ? [scene.imageUrl.trim()] : [];
}

/** 项目刷新时保留同项目内仍存在的选中分镜；切换项目则清空选择。 */
export function resolveSelectedSceneId(
  currentProjectId: string | null | undefined,
  nextProjectId: string,
  selectedSceneId: string | null | undefined,
  sceneIds: string[],
): string | null {
  if (currentProjectId !== nextProjectId || !selectedSceneId) return null;
  return sceneIds.includes(selectedSceneId) ? selectedSceneId : null;
}
