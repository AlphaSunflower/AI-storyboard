import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMediaStore } from './mediaStore';
import type { MediaAsset } from '../types';

const asset = (id: string): MediaAsset => ({
  id,
  kind: 'image',
  name: `${id}.png`,
  mimeType: 'image/png',
  bytes: 4,
  width: 1,
  height: 1,
  originalBlob: new Blob(['test'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['test'], { type: 'image/webp' }),
  previewBlob: new Blob(['test'], { type: 'image/webp' }),
  operation: { type: 'import', params: {} },
  libraryState: 'project',
  createdAt: Date.now(),
});

describe('media store batches', () => {
  beforeEach(() => useMediaStore.getState().clearAssets());

  it('adds a large batch in one store notification', () => {
    const listener = vi.fn();
    const unsubscribe = useMediaStore.subscribe(listener);
    useMediaStore.getState().upsertAssets(Array.from({ length: 64 }, (_, index) => asset(`slice-${index}`)));
    unsubscribe();
    expect(listener).toHaveBeenCalledTimes(1);
    expect(Object.keys(useMediaStore.getState().assets)).toHaveLength(48);
    expect(Object.keys(useMediaStore.getState().metadata)).toHaveLength(64);
  });
});
