import { beforeEach, describe, expect, it, vi } from 'vitest';

const projectStorage = vi.hoisted(() => ({
  firstCanvasImageAssetId: vi.fn(),
  getAssetVariant: vi.fn(),
}));

const canvasAssets = vi.hoisted(() => ({
  download: vi.fn(),
}));

vi.mock('./projectStorage', () => ({
  firstCanvasImageAssetId: projectStorage.firstCanvasImageAssetId,
  getAssetVariant: projectStorage.getAssetVariant,
}));

vi.mock('./canvasAssets', () => ({
  downloadCanvasAssetBlob: canvasAssets.download,
}));

import { clearCanvasCoverCache, resolveCanvasCoverBlob } from './canvasCover';

const thumbnail = () => new Blob(['thumbnail'], { type: 'image/webp' });

describe('canvas cover resolution', () => {
  beforeEach(() => {
    clearCanvasCoverCache();
    projectStorage.firstCanvasImageAssetId.mockReset();
    projectStorage.getAssetVariant.mockReset();
    canvasAssets.download.mockReset();
  });

  it('prefers the server cover asset and its local thumbnail', async () => {
    const local = thumbnail();
    projectStorage.getAssetVariant.mockResolvedValue(local);

    await expect(resolveCanvasCoverBlob('canvas-1', 'asset-1')).resolves.toBe(local);
    expect(projectStorage.getAssetVariant).toHaveBeenCalledWith('asset-1', 'thumbnail');
    expect(canvasAssets.download).not.toHaveBeenCalled();
  });

  it('falls back to the original file when no local variant is cached', async () => {
    const remote = new Blob(['remote'], { type: 'image/png' });
    projectStorage.getAssetVariant.mockResolvedValue(undefined);
    canvasAssets.download.mockResolvedValue(remote);

    await expect(resolveCanvasCoverBlob('canvas-1', 'asset-1')).resolves.toBe(remote);
    expect(canvasAssets.download).toHaveBeenCalledWith({ id: 'asset-1', type: 'image', mimeType: 'image/*', url: '' });
  });

  it('falls back to the earliest local image when the backend sends no cover', async () => {
    const local = thumbnail();
    projectStorage.firstCanvasImageAssetId.mockResolvedValue('local-asset-1');
    projectStorage.getAssetVariant.mockImplementation(async (_id: string, variant: string) =>
      variant === 'thumbnail' ? undefined : local);

    await expect(resolveCanvasCoverBlob('canvas-2')).resolves.toBe(local);
    expect(projectStorage.firstCanvasImageAssetId).toHaveBeenCalledWith('canvas-2');
    expect(projectStorage.getAssetVariant).toHaveBeenCalledWith('local-asset-1', 'thumbnail');
    expect(projectStorage.getAssetVariant).toHaveBeenCalledWith('local-asset-1', 'preview');
  });

  it('stays empty when nothing resolves, and retries once an asset shows up', async () => {
    projectStorage.getAssetVariant.mockResolvedValue(undefined);
    canvasAssets.download.mockRejectedValue(new Error('HTTP 404'));
    projectStorage.firstCanvasImageAssetId.mockResolvedValue(undefined);

    await expect(resolveCanvasCoverBlob('canvas-3', 'asset-3')).resolves.toBeNull();
    await expect(resolveCanvasCoverBlob('canvas-3', 'asset-3')).resolves.toBeNull();
    // 失败不进缓存：素材稍后落库时卡片重挂载仍能取到图。
    expect(canvasAssets.download).toHaveBeenCalledTimes(2);

    canvasAssets.download.mockResolvedValue(thumbnail());
    await expect(resolveCanvasCoverBlob('canvas-3', 'asset-3')).resolves.not.toBeNull();
  });

  it('shares one in-flight resolution between identical canvas cards', async () => {
    const local = thumbnail();
    projectStorage.getAssetVariant.mockResolvedValue(local);

    const [first, second] = await Promise.all([
      resolveCanvasCoverBlob('canvas-4', 'asset-4'),
      resolveCanvasCoverBlob('canvas-4', 'asset-4'),
    ]);
    expect(first).toBe(local);
    expect(second).toBe(local);
    expect(projectStorage.getAssetVariant).toHaveBeenCalledTimes(1);
  });
});
