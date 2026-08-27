import { beforeEach, describe, expect, it } from 'vitest';
import { clearLocalData, collectAssetGarbage, createCanvas, createProject, getAsset, getAssetMetadata, saveAsset, saveAssetToLibrary, saveCanvas } from './projectStorage';
import type { PersistedAsset } from '../types';

const asset = (id: string, derivedFrom?: string): PersistedAsset => ({
  id, kind: 'image', name: `${id}.png`, mimeType: 'image/png', bytes: 4, width: 1, height: 1,
  originalBlob: new Blob(['test'], { type: 'image/png' }), thumbnailBlob: new Blob(['test'], { type: 'image/webp' }), previewBlob: new Blob(['test'], { type: 'image/webp' }),
  derivedFrom, operation: { type: 'import', params: {} }, libraryState: 'project', createdAt: Date.now(),
});

describe('asset references', () => {
  beforeEach(() => clearLocalData());

  it('keeps project references and lineage ancestors, then reclaims orphans', async () => {
    await saveAsset(asset('source')); await saveAsset(asset('child', 'source')); await saveAsset(asset('orphan'));
    const project = await createProject('refs');
    const canvas = await createCanvas(project.id, '画布 1');
    canvas.nodes = [{ id: 'image-n', type: 'media', position: { x: 0, y: 0 }, data: { kind: 'image', label: 'child', prompt: '', status: 'success', accent: '#fff', assetId: 'child' } }];
    await saveCanvas(canvas); await collectAssetGarbage();
    expect(await getAsset('child')).toBeDefined(); expect(await getAsset('source')).toBeDefined(); expect(await getAsset('orphan')).toBeUndefined();
  });

  it('keeps global library assets without a project reference', async () => {
    await saveAsset(asset('library')); await saveAssetToLibrary('library'); await collectAssetGarbage();
    expect(await getAsset('library')).toBeDefined();
  });

  it('reads metadata without requiring callers to materialize asset blobs', async () => {
    await saveAsset(asset('metadata'));
    const metadata = await getAssetMetadata('metadata');
    expect(metadata).toMatchObject({ id: 'metadata', width: 1, height: 1 });
    expect(metadata).not.toHaveProperty('originalBlob');
  });
});
