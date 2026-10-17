// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assetCacheUsage, clearLocalData, cleanupGenerationQueue, collectAssetGarbage, createCanvas, createProject, evictAssetCache, flushScheduledAssetGarbageCollection, getAsset, getAssetMetadata, getAssetVariant, getCanvasRevision, listGenerationQueueItems, loadCanvas, putGenerationQueueItem, saveAsset, saveAssetToLibrary, saveCanvas, scheduleAssetGarbageCollection } from './projectStorage';
import type { CanvasDocumentV1, PersistedAsset } from '../types';
import { clearToken, setJobToken } from './jobAuth';
import type { GenerationQueueItem } from '../canvas/generationQueue';

const asset = (id: string, derivedFrom?: string): PersistedAsset => ({
  id, kind: 'image', name: `${id}.png`, mimeType: 'image/png', bytes: 4, width: 1, height: 1,
  originalBlob: new Blob(['test'], { type: 'image/png' }), thumbnailBlob: new Blob(['test'], { type: 'image/webp' }), previewBlob: new Blob(['test'], { type: 'image/webp' }),
  derivedFrom, operation: { type: 'import', params: {} }, libraryState: 'project', createdAt: Date.now(),
});

describe('asset references', () => {
  beforeEach(async () => {
    await clearLocalData();
    clearToken();
    setJobToken(validJwt(), 'user-1');
  });
  afterEach(() => {
    clearToken();
    vi.unstubAllGlobals();
  });

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

  it('reads one asset variant without materializing the other blobs', async () => {
    await saveAsset(asset('variant'));
    expect(await getAssetVariant('variant', 'thumbnail')).toBeDefined();
    await evictAssetCache({ limit: 0, now: Date.now() + 11 * 60 * 1000 });
    expect(await getAssetMetadata('variant')).toBeDefined();
    expect(await getAsset('variant')).toBeUndefined();
  });

  it('evicts old unpinned cache entries but keeps library assets pinned', async () => {
    await saveAsset(asset('old'));
    await saveAsset(asset('library'));
    await saveAssetToLibrary('library');

    const evicted = await evictAssetCache({ limit: 0, now: Date.now() + 11 * 60 * 1000 });

    expect(evicted).toContain('old');
    expect(evicted).not.toContain('library');
    expect(await assetCacheUsage()).toBeGreaterThan(0);
  });

  it('cleans terminal generation queue items after the retention window', async () => {
    const now = Date.now();
    const queueItem = (id: string, status: GenerationQueueItem['status'], updatedAt: number): GenerationQueueItem => ({
      id,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      producerNodeId: 'node-1',
      generationRecordId: id,
      requestSnapshot: { capabilityId: 'image.generate', sourceAssetIds: [], params: {} },
      status,
      attempt: 1,
      createdAt: updatedAt,
      updatedAt,
    });
    await putGenerationQueueItem(queueItem('old-terminal', 'terminal', now - 10_000));
    await putGenerationQueueItem(queueItem('recent-terminal', 'terminal', now));
    await putGenerationQueueItem(queueItem('old-running', 'running', now - 10_000));

    expect(await cleanupGenerationQueue(5_000)).toEqual(['old-terminal']);
    expect((await listGenerationQueueItems('project-1', 'canvas-1')).map((item) => item.id).sort())
      .toEqual(['old-running', 'recent-terminal']);
  });

  it('flushes scheduled asset garbage collection with protected ids', async () => {
    await saveAsset(asset('protected'));
    await saveAsset(asset('orphan'));

    scheduleAssetGarbageCollection(['protected']);
    await flushScheduledAssetGarbageCollection();

    expect(await getAsset('protected')).toBeDefined();
    expect(await getAsset('orphan')).toBeUndefined();
  });

  it('loads an empty backend-created canvas without a local cache', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      graph: {
        schemaVersion: 2,
        nodes: [],
        edges: [],
        viewport: { x: 0, y: 0, zoom: 1 },
      },
      revision: 0,
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    const canvas = await loadCanvas('canvas-new', 'project-1');

    expect(canvas).toMatchObject({
      id: 'canvas-new',
      projectId: 'project-1',
      nodes: [],
      edges: [],
    });
  });

  it('reads the server graph revision for live refresh polling', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ revision: 12 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getCanvasRevision('canvas-1')).resolves.toBe(12);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/canvases/canvas-1/revision');
  });

  it('rebases a 40901 conflict exactly once and keeps the local graph', async () => {
    const putBodies: Array<{ baseRevision: number; graph: { nodes: Array<{ data: { label: string } }> } }> = [];
    vi.stubGlobal('fetch', conflictFetch(putBodies));

    const localCanvas: CanvasDocumentV1 = {
      schemaVersion: 1,
      id: 'canvas-conflict',
      projectId: 'project-1',
      title: '画布',
      nodes: [{ id: 'image-n', type: 'media', position: { x: 0, y: 0 }, data: { kind: 'image', label: '本地编辑', prompt: '', status: 'success', accent: '#fff' } }] as CanvasDocumentV1['nodes'],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      revision: 3,
      createdAt: 1,
      updatedAt: 1,
    };
    const saved = await saveCanvas(localCanvas);

    // 只重试一次：第二次 PUT 用服务端最新 revision，本地图原样提交，revision 与服务端对齐
    expect(putBodies).toHaveLength(2);
    expect(putBodies[0].baseRevision).toBe(3);
    expect(putBodies[1].baseRevision).toBe(7);
    expect(putBodies[1].graph.nodes[0].data.label).toBe('本地编辑');
    expect(saved.revision).toBe(8);
  });

  it('gives up after a single 40901 retry and surfaces the conflict', async () => {
    let puts = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.includes('/api/auth/')) return jsonResponse({ data: { accessToken: validJwt(), userId: 'user-1' } });
      if (method === 'PATCH') return jsonResponse({ ok: true });
      if (method === 'GET' && url.includes('/revision')) return jsonResponse({ revision: 7 });
      if (method === 'PUT') {
        puts += 1;
        return jsonResponse({ code: 40901, message: '画布已被他人修改，请刷新后重试|revision=7' }, 409);
      }
      throw new Error(`unexpected ${method} ${url}`);
    }));

    await expect(saveCanvas({
      schemaVersion: 1,
      id: 'canvas-conflict-2',
      projectId: 'project-1',
      title: '画布',
      nodes: [],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      revision: 1,
      createdAt: 1,
      updatedAt: 1,
    })).rejects.toThrow(/画布已被他人修改/);
    // 不再无限重试：恰好两次 PUT
    expect(puts).toBe(2);
  });

  it('does not fall back to a local canvas when the backend rejects ownership', async () => {
    const localCanvas = {
      schemaVersion: 1 as const,
      id: 'canvas-owned-by-another-user',
      projectId: 'project-1',
      title: '旧账号画布',
      nodes: [],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      revision: 1,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        graph: {
          schemaVersion: 2,
          nodes: [],
          edges: [],
          viewport: localCanvas.viewport,
        },
        revision: 1,
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })));
    await saveCanvas(localCanvas);

    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: 40301, message: '项目不存在或无权访问' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } },
    )));

    await expect(loadCanvas(localCanvas.id)).rejects.toThrow('项目不存在或无权访问');
  });
});

function validJwt(): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;
}

/** apiJson 只需要 ok/status/json 三个成员，直接用对象比构造 Response 更可控。 */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

/**
 * 40901 rebase 用的 fetch 脚本：首次 PUT 冲突、`/revision` 返回服务端最新 revision、
 * 第二次 PUT 成功。同时应答 PATCH 标题与 /api/auth/refresh（不属于被测路径）。
 */
function conflictFetch(putBodies: Array<{ baseRevision: number; graph: { nodes: Array<{ data: { label: string } }> } }>) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    if (url.includes('/api/auth/')) return jsonResponse({ data: { accessToken: validJwt(), userId: 'user-1' } });
    if (method === 'PATCH') return jsonResponse({ ok: true });
    if (method === 'GET' && url.includes('/revision')) return jsonResponse({ revision: 7 });
    if (method === 'PUT') {
      const body = JSON.parse(String(init?.body ?? '{}')) as typeof putBodies[number];
      putBodies.push(body);
      if (putBodies.length === 1) {
        return jsonResponse({ code: 40901, message: '画布已被他人修改，请刷新后重试|revision=7' }, 409);
      }
      return jsonResponse({ graph: body.graph, revision: 8 });
    }
    throw new Error(`unexpected ${method} ${url}`);
  });
}
