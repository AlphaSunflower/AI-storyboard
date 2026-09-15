import { describe, expect, it } from 'vitest';
import { resolveStoryboardReferenceAssetIds } from './storyboardReferences';

const catalog = [
  { index: 1, assetId: 'asset-1', nodeId: 'node-1' },
  { index: 2, assetId: 'asset-2', nodeId: 'node-2' },
];

describe('resolveStoryboardReferenceAssetIds', () => {
  it('maps @image and legacy @图 hints back to real canvas assets', () => {
    expect(resolveStoryboardReferenceAssetIds(['@image#2', '@图1'], catalog)).toEqual(['asset-2', 'asset-1']);
  });

  it('uses the only available reference when the model omits hints', () => {
    expect(resolveStoryboardReferenceAssetIds([], [catalog[0]])).toEqual(['asset-1']);
  });
});
