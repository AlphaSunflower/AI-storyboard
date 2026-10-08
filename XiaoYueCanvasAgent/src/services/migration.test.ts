import { describe, expect, it } from 'vitest';
import type { ProjectDocumentV1 } from '../types';

// 手动构造一个 v2 结构的旧库（项目直接含图，无 canvases store）。
// 之后动态 import projectStorage（openDB v3），触发 upgrade 迁移。
const seedLegacyDatabase = () => new Promise<void>((resolve, reject) => {
  const request = indexedDB.open('morrow-creative-canvas-v1', 2);
  request.onupgradeneeded = () => {
    const db = request.result;
    const transaction = request.transaction!;
    if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
    if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
    if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
    const legacy: ProjectDocumentV1 = {
      schemaVersion: 1,
      id: 'legacy-project',
      title: '旧项目',
      nodes: [{ id: 'image-1', type: 'media', position: { x: 10, y: 20 }, data: { kind: 'image', label: '图', prompt: '', status: 'success', accent: '#fff', assetId: 'asset-1' } }],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      createdAt: 1,
      updatedAt: 1,
    };
    transaction.objectStore('projects').put(legacy);
    transaction.objectStore('assets').put({
      id: 'asset-1',
      kind: 'image',
      name: 'a.png',
      mimeType: 'image/png',
      bytes: 4,
      width: 1,
      height: 1,
      originalBlob: new Blob(['test'], { type: 'image/png' }),
      thumbnailBlob: new Blob(['test'], { type: 'image/webp' }),
      previewBlob: new Blob(['test'], { type: 'image/webp' }),
      libraryState: 'project',
      createdAt: 1,
    });
  };
  request.onsuccess = () => { request.result.close(); resolve(); };
  request.onerror = () => reject(request.error);
});

describe('v2 → v5 migration', () => {
  it('splits legacy projects into project + independent canvas and backfills asset provenance', async () => {
    await seedLegacyDatabase();
    const { listProjects, listCanvases, loadCanvas, getAsset, getAssetMetadata } = await import('./projectStorage');

    const projects = await listProjects();
    expect(projects).toHaveLength(1);
    // 迁移后 id 由后端生成（非原 legacy 浏览器 UUID）；内容(title/画布/节点)保留
    const migratedId = projects[0].id;
    expect(migratedId).not.toBe('legacy-project'); // 后端生成新 id，不保留浏览器 UUID
    expect(projects[0]).toMatchObject({ title: '旧项目', canvasCount: 1, nodeCount: 1 });

    const canvases = await listCanvases(migratedId);
    expect(canvases).toHaveLength(1);

    const canvas = await loadCanvas(canvases[0].id);
    expect(canvas).toBeDefined();
    expect(canvas!.projectId).toBe(migratedId);
    expect(canvas!.nodes).toHaveLength(1);
    expect(canvas!.nodes[0].data.assetId).toBe('asset-1');

    // v5 migration keeps asset provenance aligned with the backend project/canvas that now owns the graph.
    const metadata = await getAssetMetadata('asset-1');
    expect(metadata?.projectId).toBe(migratedId);
    expect(metadata?.canvasId).toBe(canvas!.id);
    expect(await getAsset('asset-1')).toBeDefined();
  });
});
