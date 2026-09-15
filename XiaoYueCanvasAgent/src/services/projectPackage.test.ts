import { strFromU8, unzipSync, zipSync } from 'fflate';
import { beforeEach, describe, expect, it } from 'vitest';
import { exportProjectPackage, importProjectPackage } from './projectPackage';
import { clearLocalData, createCanvas, createProject, getAsset, loadCanvas, saveAsset, saveCanvas } from './projectStorage';
import type { PersistedAsset } from '../types';

const png = new Uint8Array([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,2,3,4]);
const webp = new Uint8Array([0x52,0x49,0x46,0x46,4,0,0,0,0x57,0x45,0x42,0x50]);

describe('.morrow packages', () => {
  beforeEach(() => clearLocalData());

  it('round-trips graph data and real blobs with checksums', async () => {
    const source: PersistedAsset = {
      id: 'asset-original', kind: 'image', name: 'sample.png', mimeType: 'image/png', bytes: png.byteLength, width: 10, height: 8,
      originalBlob: new Blob([png], { type: 'image/png' }), thumbnailBlob: new Blob([webp], { type: 'image/webp' }), previewBlob: new Blob([webp], { type: 'image/webp' }),
      operation: { type: 'import', params: {} }, libraryState: 'project', createdAt: 1,
    };
    await saveAsset(source);
    const project = await createProject('Portable');
    const canvas = await createCanvas(project.id, '画布 1');
    canvas.nodes = [{ id: 'image-1', type: 'media', position: { x: 4, y: 5 }, data: { kind: 'image', label: 'sample', prompt: '', status: 'success', accent: '#fff', assetId: source.id } }];
    canvas.generationRecords = [{
      id: 'generation-1',
      producerNodeId: 'producer-1',
      actionId: 'generate',
      attempt: 1,
      status: 'completed',
      materializationStatus: 'completed',
      materializedAssetIds: [source.id],
      resultNodeIds: ['image-1'],
      sources: [{ nodeId: 'source-node-1', assetId: source.id, role: 'source', order: 0 }],
      prompt: 'sample',
      effectivePrompt: 'sample',
      params: {},
      startedAt: 1,
    }];
    await saveCanvas(canvas);
    const bundle = await exportProjectPackage(project.id);
    const entries = unzipSync(new Uint8Array(await bundle.arrayBuffer()));
    const manifest = JSON.parse(strFromU8(entries['manifest.json'])) as { assets: Array<{ checksums: Record<string,string> }> };
    expect(manifest.assets[0].checksums.original).toMatch(/^[a-f0-9]{64}$/);

    await clearLocalData();
    const restored = await importProjectPackage(new File([bundle], 'portable.morrow', { type: 'application/x-morrow-project' }));
    const loadedCanvas = await loadCanvas(restored.canvases[0].id);
    expect(loadedCanvas?.nodes).toHaveLength(1);
    expect(loadedCanvas?.generationRecords).toHaveLength(1);
    expect(loadedCanvas?.generationRecords?.[0].resultNodeIds[0]).toBe(loadedCanvas.nodes[0].id);
    expect(loadedCanvas?.generationRecords?.[0].materializedAssetIds[0]).toBe(loadedCanvas.nodes[0].data.assetId);
    const restoredAsset = await getAsset(loadedCanvas!.nodes[0].data.assetId!);
    expect(new Uint8Array(await restoredAsset!.originalBlob.arrayBuffer())).toEqual(png);
  });

  it('rejects a package whose asset bytes no longer match the manifest', async () => {
    const source: PersistedAsset = {
      id: 'asset-original', kind: 'image', name: 'sample.png', mimeType: 'image/png', bytes: png.byteLength, width: 10, height: 8,
      originalBlob: new Blob([png], { type: 'image/png' }), thumbnailBlob: new Blob([webp], { type: 'image/webp' }), previewBlob: new Blob([webp], { type: 'image/webp' }),
      operation: { type: 'import', params: {} }, libraryState: 'project', createdAt: 1,
    };
    await saveAsset(source);
    const project = await createProject('Broken');
    const canvas = await createCanvas(project.id, '画布 1');
    canvas.nodes = [{ id: 'image-1', type: 'media', position: { x: 0, y: 0 }, data: { kind: 'image', label: 'sample', prompt: '', status: 'success', accent: '#fff', assetId: source.id } }];
    await saveCanvas(canvas);
    const bundle = await exportProjectPackage(project.id);
    const entries = unzipSync(new Uint8Array(await bundle.arrayBuffer()));
    const originalPath = Object.keys(entries).find((path) => path.endsWith('/original'))!; entries[originalPath][8] ^= 0xff;
    const { zipSync } = await import('fflate'); const damaged = new File([zipSync(entries) as Uint8Array<ArrayBuffer>], 'broken.morrow');
    await expect(importProjectPackage(damaged)).rejects.toThrow('校验失败');
  });

  it('imports legacy v1 and v2 manifests into v3 canvas documents', async () => {
    await clearLocalData();
    const project = await createProject('Legacy');
    const canvas = await createCanvas(project.id, '旧画布');
    canvas.nodes = [{ id: 'text-1', type: 'text', position: { x: 0, y: 0 }, data: { kind: 'text', label: '文字', prompt: 'hi', status: 'idle', accent: '#fff' } }];
    await saveCanvas(canvas);
    const bundle = await exportProjectPackage(project.id);
    const entries = unzipSync(new Uint8Array(await bundle.arrayBuffer()));
    const manifest = JSON.parse(strFromU8(entries['manifest.json'])) as {
      packageVersion: number;
      project: Record<string, unknown>;
      canvases?: unknown[];
    };

    manifest.packageVersion = 1;
    manifest.project = {
      schemaVersion: 1,
      id: project.id,
      title: 'Legacy',
      nodes: canvas.nodes,
      edges: canvas.edges,
      viewport: canvas.viewport,
      createdAt: 1,
      updatedAt: 1,
    };
    delete manifest.canvases;
    entries['manifest.json'] = new TextEncoder().encode(JSON.stringify(manifest));
    const imported = await importProjectPackage(new File([zipSync(entries)], 'legacy-v1.morrow'));
    expect(imported.canvases).toHaveLength(1);
    expect(imported.canvases[0].nodes[0].data.kind).toBe('text');
  });
});
