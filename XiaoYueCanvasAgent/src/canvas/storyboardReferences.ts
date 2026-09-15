import { findReferenceTokens } from './referenceTokenEditing';

export interface StoryboardReferenceCatalogItem {
  index: number;
  assetId: string;
  nodeId: string;
}

export function resolveStoryboardReferenceAssetIds(
  hints: string[],
  catalog: StoryboardReferenceCatalogItem[],
): string[] {
  const byIndex = new Map(catalog.map((item) => [item.index, item]));
  const assetIds = new Set<string>();
  for (const hint of hints) {
    for (const token of findReferenceTokens(hint)) {
      const item = byIndex.get(token.imageNumber);
      if (item) assetIds.add(item.assetId);
    }
  }
  if (!assetIds.size && catalog.length === 1) assetIds.add(catalog[0].assetId);
  return [...assetIds];
}
