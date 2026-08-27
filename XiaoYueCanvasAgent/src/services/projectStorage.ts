import { openDB } from 'idb';
import type { AssetMetadata, CanvasDocumentV1, CanvasEdge, CanvasNode, CanvasSummary, MediaAsset, PersistedAsset, ProjectDocumentV1, ProjectDocumentV2, ProjectSummary } from '../types';
import { createId } from '../utils/id';
import { apiJson } from './morrowApi';

// Use a new versioned namespace instead of attempting to migrate the former demo prototype.
export const DB_NAME = 'morrow-creative-canvas-v1';
const DB_VERSION = 3;

type AssetVariant = 'original' | 'thumbnail' | 'preview';
interface PersistedAssetVariant { assetId: string; variant: AssetVariant; blob: Blob }

export const assetMetadata = (asset: MediaAsset | PersistedAsset): AssetMetadata => {
  const { originalBlob: _original, thumbnailBlob: _thumbnail, previewBlob: _preview, ...metadata } = asset;
  return metadata;
};

const database = openDB(DB_NAME, DB_VERSION, {
  async upgrade(db, oldVersion, _newVersion, transaction) {
    if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
    if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
    if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
    if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });

    // v2 -> v3 迁移：把旧项目（v1，含图）拆成 project(v2) + 独立 canvas，并回填资产来源信息。
    if (oldVersion >= 1 && oldVersion < 3) {
      const projectStore = transaction.objectStore('projects');
      const canvasStore = transaction.objectStore('canvases');
      const assetMetaStore = transaction.objectStore('assetMeta');
      const legacyProjects = await projectStore.getAll() as Array<ProjectDocumentV1 | ProjectDocumentV2>;
      for (const legacy of legacyProjects) {
        if ('canvases' in legacy) continue; // 已是 v2
        const canvasId = createId();
        const canvas: CanvasDocumentV1 = {
          schemaVersion: 1,
          id: canvasId,
          projectId: legacy.id,
          title: '默认画布',
          nodes: legacy.nodes,
          edges: legacy.edges,
          viewport: legacy.viewport,
          createdAt: legacy.createdAt,
          updatedAt: legacy.updatedAt,
        };
        await canvasStore.put(canvas);
        await projectStore.put({ schemaVersion: 2, id: legacy.id, title: legacy.title, canvases: [canvasId], createdAt: legacy.createdAt, updatedAt: legacy.updatedAt } satisfies ProjectDocumentV2);
        for (const node of legacy.nodes) {
          if (!node.data.assetId) continue;
          const meta = await assetMetaStore.get(node.data.assetId) as AssetMetadata | undefined;
          if (meta && !meta.projectId) await assetMetaStore.put({ ...meta, projectId: legacy.id, canvasId });
        }
      }
    }
  },
});

export const hydrateAsset = (asset: PersistedAsset): MediaAsset => asset;

export async function saveAsset(asset: MediaAsset | PersistedAsset) {
  await saveAssets([asset]);
}

export async function saveAssets(assets: Array<MediaAsset | PersistedAsset>) {
  const db = await database;
  const transaction = db.transaction(['assetMeta', 'assetVariants', 'assets'], 'readwrite');
  const metaStore = transaction.objectStore('assetMeta');
  const variantStore = transaction.objectStore('assetVariants');
  const legacyStore = transaction.objectStore('assets');
  const done = transaction.done;
  await Promise.all([
    ...assets.flatMap((asset) => [
      metaStore.put(assetMetadata(asset)),
      variantStore.put({ assetId: asset.id, variant: 'original', blob: asset.originalBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'thumbnail', blob: asset.thumbnailBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'preview', blob: asset.previewBlob } satisfies PersistedAssetVariant),
      legacyStore.delete(asset.id),
    ]),
  ]);
  await done;
}

export async function getAsset(assetId: string) {
  const db = await database;
  const metadata = await db.get('assetMeta', assetId) as AssetMetadata | undefined;
  if (metadata) {
    const transaction = db.transaction('assetVariants', 'readonly');
    const done = transaction.done;
    const [original, thumbnail, preview] = await Promise.all([
      transaction.store.get([assetId, 'original']) as Promise<PersistedAssetVariant | undefined>,
      transaction.store.get([assetId, 'thumbnail']) as Promise<PersistedAssetVariant | undefined>,
      transaction.store.get([assetId, 'preview']) as Promise<PersistedAssetVariant | undefined>,
    ]);
    await done;
    if (!original || !thumbnail || !preview) return undefined;
    return { ...metadata, originalBlob: original.blob, thumbnailBlob: thumbnail.blob, previewBlob: preview.blob } satisfies PersistedAsset;
  }
  const legacy = await db.get('assets', assetId) as PersistedAsset | undefined;
  if (legacy) await saveAsset(legacy);
  return legacy;
}

export async function getAssetVariant(assetId: string, variant: AssetVariant) {
  const db = await database;
  const record = await db.get('assetVariants', [assetId, variant]) as PersistedAssetVariant | undefined;
  if (record) return record.blob;
  const legacy = await db.get('assets', assetId) as PersistedAsset | undefined;
  if (!legacy) return undefined;
  await saveAsset(legacy);
  return variant === 'original' ? legacy.originalBlob : variant === 'thumbnail' ? legacy.thumbnailBlob : legacy.previewBlob;
}

export async function getAssetMetadata(assetId: string) {
  const db = await database;
  const metadata = await db.get('assetMeta', assetId) as AssetMetadata | undefined;
  if (metadata) return metadata;
  const legacy = await db.get('assets', assetId) as PersistedAsset | undefined;
  if (!legacy) return undefined;
  await saveAsset(legacy);
  return assetMetadata(legacy);
}

export async function getAssetMetadatas(assetIds: Iterable<string>) {
  const records = await Promise.all([...new Set(assetIds)].map(getAssetMetadata));
  return records.filter((asset): asset is AssetMetadata => Boolean(asset));
}

export async function getAssets(assetIds: Iterable<string>) {
  const ids = [...new Set(assetIds)];
  const records = await Promise.all(ids.map(getAsset));
  return records.filter((asset): asset is PersistedAsset => Boolean(asset));
}

export async function getAssetsWithAncestors(assetIds: Iterable<string>) {
  const requested = new Set(assetIds);
  const assets = new Map<string, PersistedAsset>();
  let pending = [...requested];
  while (pending.length) {
    const records = await getAssets(pending);
    pending = [];
    records.forEach((asset) => {
      if (assets.has(asset.id)) return;
      assets.set(asset.id, asset);
      if (asset.derivedFrom && !assets.has(asset.derivedFrom)) pending.push(asset.derivedFrom);
    });
  }
  return [...assets.values()];
}

// ── 项目（实体在共享库 ai分镜 projects 表；IndexedDB 不再作为项目源）────────────────
const nowMs = () => Date.now();

export async function createProject(title = 'Untitled'): Promise<ProjectDocumentV2> {
  const p = await apiJson<{ id: string; title: string }>('/projects', { method: 'POST', body: JSON.stringify({ title }) });
  const now = nowMs();
  return { schemaVersion: 2, id: p.id, title: p.title || title, canvases: [], createdAt: now, updatedAt: now };
}

export async function saveProject(project: ProjectDocumentV2) {
  await apiJson(`/projects/${encodeURIComponent(project.id)}`, { method: 'PATCH', body: JSON.stringify({ title: project.title }) });
}

export async function loadProject(projectId: string): Promise<ProjectDocumentV2 | undefined> {
  try {
    const p = await apiJson<{ id: string; title: string }>(`/projects/${encodeURIComponent(projectId)}`);
    const now = nowMs();
    return { schemaVersion: 2, id: p.id, title: p.title, canvases: [], createdAt: now, updatedAt: now };
  } catch { return undefined; }
}

// 懒迁移：旧 IndexedDB 项目（id=浏览器生成的 UUID）→ 建后端 ai分镜 项目 + 画布元数据，重映射画布 projectId。
const BACKEND_ID_RE = /^[0-9a-f]{32}$/;

async function ensureBackendProjects() {
  const db = await database;
  const legacy = (await db.getAll('projects')) as ProjectDocumentV2[];
  if (!legacy.length) return;
  for (const p of legacy) {
    if (BACKEND_ID_RE.test(p.id)) continue; // 已是后端项目 id
    try {
      const created = await apiJson<{ id: string }>('/projects', { method: 'POST', body: JSON.stringify({ title: p.title }) });
      const localCanvases = (await db.getAll('canvases')) as CanvasDocumentV1[];
      const projectCanvases = localCanvases.filter((c) => c.projectId === p.id);
      for (const c of projectCanvases) {
        const meta = await apiJson<{ id: string }>(`/projects/${encodeURIComponent(created.id)}/canvases`, { method: 'POST', body: JSON.stringify({ title: c.title }) });
        await db.delete('canvases', c.id);
        await db.put('canvases', { ...c, id: meta.id, projectId: created.id });
      }
    } catch { /* 迁移失败不阻断（后端不可达/无 token 时跳过，下次再试） */ }
    await db.delete('projects', p.id);
  }
}

export async function listProjects(): Promise<ProjectSummary[]> {
  await ensureBackendProjects();
  const projects = await apiJson<Array<{ id: string; title: string }>>('/projects');
  const db = await database;
  const localCanvases = await db.getAll('canvases') as CanvasDocumentV1[];
  return Promise.all(projects.map(async (p) => {
    let canvasCount = 0, nodeCount = 0, assetCount = 0;
    try {
      const canvases = await apiJson<Array<{ id: string; title: string }>>(`/projects/${encodeURIComponent(p.id)}/canvases`);
      canvasCount = canvases.length;
      for (const c of canvases) {
        const doc = localCanvases.find((x) => x.id === c.id);
        if (doc) { nodeCount += doc.nodes.length; assetCount += new Set(doc.nodes.flatMap((n) => (n.data.assetId ? [n.data.assetId] : []))).size; }
      }
    } catch { /* 列表计数失败忽略 */ }
    const now = nowMs();
    return { id: p.id, title: p.title, createdAt: now, updatedAt: now, canvasCount, nodeCount, assetCount };
  }));
}

export async function deleteProject(projectId: string) {
  await apiJson(`/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' });
  const db = await database;
  const state = await db.getAll('canvases') as CanvasDocumentV1[];
  const ids = state.filter((c) => c.projectId === projectId).map((c) => c.id);
  if (ids.length) {
    const transaction = db.transaction('canvases', 'readwrite');
    await Promise.all(ids.map((id) => transaction.store.delete(id)));
    await transaction.done;
  }
}

// ── 画布：元数据(id/projectId/title)落后端 morrow_canvases，节点内容(nodes/edges/viewport)留 IndexedDB canvases store ──
export async function createCanvas(projectId: string, title = '画布'): Promise<CanvasDocumentV1> {
  const meta = await apiJson<{ id: string; projectId: string; title: string }>(`/projects/${encodeURIComponent(projectId)}/canvases`, { method: 'POST', body: JSON.stringify({ title }) });
  const now = nowMs();
  const canvas: CanvasDocumentV1 = { schemaVersion: 1, id: meta.id, projectId, title: meta.title || title, nodes: [], edges: [], viewport: { x: 180, y: 90, zoom: .7 }, createdAt: now, updatedAt: now };
  await (await database).put('canvases', canvas); // 节点内容落本地（按画布 id）
  return canvas;
}

export async function saveCanvas(canvas: CanvasDocumentV1) {
  const now = nowMs();
  await (await database).put('canvases', { ...canvas, updatedAt: now }); // 节点内容
  await apiJson(`/canvases/${encodeURIComponent(canvas.id)}`, { method: 'PATCH', body: JSON.stringify({ title: canvas.title }) }).catch(() => undefined); // 标题走后端（失败不阻断本地保存）
}

export async function loadCanvas(canvasId: string): Promise<CanvasDocumentV1 | undefined> {
  return (await database).get('canvases', canvasId) as Promise<CanvasDocumentV1 | undefined>;
}

export async function listCanvases(projectId: string): Promise<CanvasSummary[]> {
  const metas = await apiJson<Array<{ id: string; projectId: string; title: string }>>(`/projects/${encodeURIComponent(projectId)}/canvases`);
  const db = await database;
  const now = nowMs();
  const summaries = await Promise.all(metas.map(async (m) => {
    const doc = await db.get('canvases', m.id) as CanvasDocumentV1 | undefined;
    return {
      id: m.id, projectId, title: m.title, createdAt: now, updatedAt: now,
      nodeCount: doc?.nodes.length ?? 0, assetCount: new Set((doc?.nodes ?? []).flatMap((n) => (n.data.assetId ? [n.data.assetId] : []))).size,
    } satisfies CanvasSummary;
  }));
  return summaries.sort((a, b) => a.createdAt - b.createdAt);
}

export async function deleteCanvas(canvasId: string) {
  await apiJson(`/canvases/${encodeURIComponent(canvasId)}`, { method: 'DELETE' });
  await (await database).delete('canvases', canvasId);
}

export async function saveImportedProject(project: ProjectDocumentV2, canvases: CanvasDocumentV1[], assets: PersistedAsset[]) {
  const db = await database;
  const transaction = db.transaction(['projects', 'canvases', 'assets', 'assetMeta', 'assetVariants'], 'readwrite');
  const projectStore = transaction.objectStore('projects');
  const canvasStore = transaction.objectStore('canvases');
  const legacyStore = transaction.objectStore('assets');
  const metaStore = transaction.objectStore('assetMeta');
  const variantStore = transaction.objectStore('assetVariants');
  const done = transaction.done;
  await Promise.all([
    ...assets.flatMap((asset) => [
      metaStore.put(assetMetadata(asset)),
      variantStore.put({ assetId: asset.id, variant: 'original', blob: asset.originalBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'thumbnail', blob: asset.thumbnailBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'preview', blob: asset.previewBlob } satisfies PersistedAssetVariant),
      legacyStore.delete(asset.id),
    ]),
    projectStore.put(project),
    ...canvases.map((canvas) => canvasStore.put(canvas)),
  ]);
  await done;
}

export async function collectAssetGarbage(protectedAssetIds: Iterable<string> = []) {
  const db = await database;
  const [canvases, entries, assetKeys, legacyAssetKeys] = await Promise.all([
    db.getAll('canvases') as Promise<CanvasDocumentV1[]>,
    db.getAll('libraryEntries') as Promise<Array<{ assetId: string; savedAt: number }>>,
    db.getAllKeys('assetMeta') as Promise<IDBValidKey[]>,
    db.getAllKeys('assets') as Promise<IDBValidKey[]>,
  ]);
  const referenced = new Set(protectedAssetIds);
  canvases.forEach((canvas) => projectAssetIds(canvas.nodes).forEach((id) => referenced.add(id)));
  entries.forEach((entry) => referenced.add(entry.assetId));

  // Only referenced lineage records are read, so GC never materializes every asset Blob.
  const queue = [...referenced];
  while (queue.length) {
    const parentId = (await getAssetMetadata(queue.pop()!))?.derivedFrom;
    if (parentId && !referenced.has(parentId)) { referenced.add(parentId); queue.push(parentId); }
  }

  const garbage = [...new Set([...assetKeys, ...legacyAssetKeys].map(String))].filter((assetId) => !referenced.has(assetId));
  for (let offset = 0; offset < garbage.length; offset += 100) {
    const transaction = db.transaction(['assets', 'assetMeta', 'assetVariants'], 'readwrite');
    const done = transaction.done;
    await Promise.all(garbage.slice(offset, offset + 100).flatMap((assetId) => [
      transaction.objectStore('assets').delete(assetId),
      transaction.objectStore('assetMeta').delete(assetId),
      transaction.objectStore('assetVariants').delete([assetId, 'original']),
      transaction.objectStore('assetVariants').delete([assetId, 'thumbnail']),
      transaction.objectStore('assetVariants').delete([assetId, 'preview']),
    ]));
    await done;
  }
  return garbage;
}

// ── 资产库 ──────────────────────────────────────────────────────────────
export async function saveAssetToLibrary(assetId: string) {
  const current = await getAsset(assetId);
  if (!current) throw new Error('素材文件不存在');
  const db = await database;
  const transaction = db.transaction(['assetMeta', 'libraryEntries'], 'readwrite');
  const done = transaction.done;
  await transaction.objectStore('assetMeta').put({ ...assetMetadata(current), libraryState: 'global' });
  await transaction.objectStore('libraryEntries').put({ assetId, savedAt: Date.now() });
  await done;
}

export async function listLibraryAssets() {
  const db = await database;
  const entries = await db.getAll('libraryEntries') as Array<{ assetId: string; savedAt: number }>;
  const assets = await Promise.all(entries.sort((a, b) => b.savedAt - a.savedAt).map((entry) => getAsset(entry.assetId)));
  return assets.filter((asset): asset is PersistedAsset => Boolean(asset));
}

// 项目资产库：属于指定项目的所有资产（导入 + 派生）。
export async function listProjectAssets(projectId: string) {
  const db = await database;
  const metas = await db.getAll('assetMeta') as AssetMetadata[];
  const ids = metas.filter((meta) => meta.projectId === projectId).map((meta) => meta.id);
  return getAssets(ids);
}

// 全局总资产库：所有 libraryState === 'global' 的资产（含其来源项目信息）。
export async function listGlobalAssets() {
  const db = await database;
  const metas = await db.getAll('assetMeta') as AssetMetadata[];
  const ids = metas.filter((meta) => meta.libraryState === 'global').map((meta) => meta.id);
  return getAssets(ids);
}

export async function setSetting<T>(key: string, value: T) {
  await (await database).put('settings', value, key);
}

export async function getSetting<T>(key: string) {
  return (await database).get('settings', key) as Promise<T | undefined>;
}

export async function clearLocalData() {
  const db = await database;
  const transaction = db.transaction(['projects', 'canvases', 'assets', 'assetMeta', 'assetVariants', 'libraryEntries', 'settings'], 'readwrite');
  const done = transaction.done;
  await Promise.all([
    transaction.objectStore('projects').clear(),
    transaction.objectStore('canvases').clear(),
    transaction.objectStore('assets').clear(),
    transaction.objectStore('assetMeta').clear(),
    transaction.objectStore('assetVariants').clear(),
    transaction.objectStore('libraryEntries').clear(),
    transaction.objectStore('settings').clear(),
  ]);
  await done;
}

export function projectAssetIds(nodes: CanvasNode[]) {
  return new Set(nodes.flatMap((node) => node.data.assetId ? [node.data.assetId] : []));
}

export function cloneProjectGraph(nodes: CanvasNode[], edges: CanvasEdge[]) {
  return { nodes: structuredClone(nodes), edges: structuredClone(edges) };
}
