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
    transaction.objectStore('assetMeta').put({ id: 'asset-1', kind: 'image', name: 'a.png', mimeType: 'image/png', bytes: 1, width: 1, height: 1, libraryState: 'project', createdAt: 1 });
  };
  request.onsuccess = () => { request.result.close(); resolve(); };
  request.onerror = () => reject(request.error);
});

describe('v2 → v3 migration', () => {
  it('splits legacy projects into project + independent canvas and backfills asset provenance', async () => {
    await seedLegacyDatabase();
    const { listProjects, listCanvases, loadCanvas, getAssetMetadata } = await import('./projectStorage');

    const projects = await listProjects();
    expect(projects).toHaveLength(1);
    expect(projects[0]).toMatchObject({ id: 'legacy-project', title: '旧项目', canvasCount: 1, nodeCount: 1 });

    const canvases = await listCanvases('legacy-project');
    expect(canvases).toHaveLength(1);

    const canvas = await loadCanvas(canvases[0].id);
    expect(canvas).toBeDefined();
    expect(canvas!.projectId).toBe('legacy-project');
    expect(canvas!.nodes).toHaveLength(1);
    expect(canvas!.nodes[0].data.assetId).toBe('asset-1');

    const metadata = await getAssetMetadata('asset-1');
    expect(metadata?.projectId).toBe('legacy-project');
    expect(metadata?.canvasId).toBe(canvas!.id);
  });
});
