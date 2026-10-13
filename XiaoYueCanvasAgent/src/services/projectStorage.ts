import { openDB } from 'idb';
import type { IDBPDatabase } from 'idb';
import type { AssetCacheEntry, AssetMetadata, CanvasDocumentV1, CanvasEdge, CanvasNode, CanvasSummary, LegacyProjectDocument, MediaAsset, PersistedAsset, ProjectDocumentV1, ProjectDocumentV2, ProjectSummary } from '../types';
import type { Viewport } from '@xyflow/react';
import { createId } from '../utils/id';
import { apiJson } from './morrowApi';
import { migrateCanvasGraphV1ToV2 } from '../canvas/graphMigration';
import { mergedGenerationKind } from '../canvas/generationModes';
import type { GenerationQueueItem } from '../canvas/generationQueue';

// Use a new versioned namespace instead of attempting to migrate the former demo prototype.
export const DB_NAME = 'morrow-creative-canvas-v1';
const DB_VERSION = 5;

export const ASSET_CACHE_MAX_BYTES = 500 * 1024 * 1024;
export const ASSET_CACHE_QUOTA_RATIO = .6;
export const ASSET_CACHE_TARGET_RATIO = .85;
export const ASSET_CACHE_MIN_AGE_MS = 10 * 60 * 1000;
export const GENERATION_QUEUE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

type AssetVariant = 'original' | 'thumbnail' | 'preview';
interface PersistedAssetVariant { assetId: string; variant: AssetVariant; blob: Blob }

export const assetMetadata = (asset: MediaAsset | PersistedAsset): AssetMetadata => {
  const { originalBlob: _original, thumbnailBlob: _thumbnail, previewBlob: _preview, ...metadata } = asset;
  return metadata;
};

const createIndex = (store: any, name: string, keyPath: string | string[], options?: IDBIndexParameters) => {
  if (!store.indexNames.contains(name)) store.createIndex(name, keyPath, options);
};

const copyObjectStore = async (
  source: any,
  target: any,
) => {
  let cursor = await source.openCursor();
  while (cursor) {
    await target.put(cursor.value);
    cursor = await cursor.continue();
  }
};

let database: Promise<IDBPDatabase>;

const openDatabase = () => openDB(DB_NAME, DB_VERSION, {
  async upgrade(db, oldVersion, _newVersion, transaction) {
    if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
    if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
    if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
    if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('generationQueue')) db.createObjectStore('generationQueue', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('assetCacheEntries')) db.createObjectStore('assetCacheEntries', { keyPath: 'assetId' });
    if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
    if (!db.objectStoreNames.contains('legacyAssets')) db.createObjectStore('legacyAssets', { keyPath: 'id' });

    createIndex(transaction.objectStore('assetMeta'), 'by-projectId', 'projectId');
    createIndex(transaction.objectStore('assetMeta'), 'by-canvasId', 'canvasId');
    createIndex(transaction.objectStore('assetMeta'), 'by-libraryState', 'libraryState');
    createIndex(transaction.objectStore('canvases'), 'by-projectId', 'projectId');
    createIndex(transaction.objectStore('canvases'), 'by-updatedAt', 'updatedAt');
    createIndex(transaction.objectStore('assetCacheEntries'), 'by-lastAccessedAt', 'lastAccessedAt');
    createIndex(transaction.objectStore('generationQueue'), 'by-projectId', 'projectId');
    createIndex(transaction.objectStore('generationQueue'), 'by-canvasId', 'canvasId');
    createIndex(transaction.objectStore('generationQueue'), 'by-project-canvas', ['projectId', 'canvasId']);
    createIndex(transaction.objectStore('generationQueue'), 'by-status-updatedAt', ['status', 'updatedAt']);
    createIndex(transaction.objectStore('generationQueue'), 'by-updatedAt', 'updatedAt');

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

    if (oldVersion < 5) {
      if (db.objectStoreNames.contains('projects')) {
        await copyObjectStore(transaction.objectStore('projects'), transaction.objectStore('legacyProjects'));
        db.deleteObjectStore('projects');
      }
      if (db.objectStoreNames.contains('assets')) {
        await copyObjectStore(transaction.objectStore('assets'), transaction.objectStore('legacyAssets'));
        db.deleteObjectStore('assets');
      }
    }
  },
  blocked() {
    console.warn('IndexedDB upgrade is blocked by another Morrow tab.');
  },
  blocking() {
    void database?.then((db) => db.close()).catch(() => undefined);
  },
  terminated() {
    database = openDatabase();
  },
});

database = openDatabase();

export const hydrateAsset = (asset: PersistedAsset): MediaAsset => asset;

const assetCacheBytes = (asset: MediaAsset | PersistedAsset) =>
  asset.originalBlob.size + asset.thumbnailBlob.size + asset.previewBlob.size;

const cacheEntryFor = (asset: MediaAsset | PersistedAsset, pinned = asset.libraryState === 'global'): AssetCacheEntry => ({
  assetId: asset.id,
  bytes: assetCacheBytes(asset) || asset.bytes,
  lastAccessedAt: Date.now(),
  cached: true,
  pinned,
});

export async function touchAssetCache(assetId: string) {
  const db = await database;
  const transaction = db.transaction('assetCacheEntries', 'readwrite');
  const entry = await transaction.store.get(assetId) as AssetCacheEntry | undefined;
  if (entry?.cached) await transaction.store.put({ ...entry, lastAccessedAt: Date.now() });
  await transaction.done;
}

export async function hasAssetVariants(assetId: string) {
  const db = await database;
  const entry = await db.get('assetCacheEntries', assetId) as AssetCacheEntry | undefined;
  return Boolean(entry?.cached);
}

export async function assetCacheUsage() {
  const entries = await (await database).getAll('assetCacheEntries') as AssetCacheEntry[];
  return entries.reduce((total, entry) => total + (entry.cached ? Number(entry.bytes) || 0 : 0), 0);
}

export async function assetCacheLimit() {
  let estimate: StorageEstimate | undefined;
  try {
    estimate = await globalThis.navigator?.storage?.estimate?.();
  } catch {
    estimate = undefined;
  }
  const quota = estimate?.quota ?? 0;
  return quota > 0
    ? Math.min(ASSET_CACHE_MAX_BYTES, quota * ASSET_CACHE_QUOTA_RATIO)
    : ASSET_CACHE_MAX_BYTES;
}

export async function evictAssetCache(options: { now?: number; limit?: number } = {}) {
  const db = await database;
  const now = options.now ?? Date.now();
  const limit = options.limit ?? await assetCacheLimit();
  let usage = await assetCacheUsage();
  if (usage <= limit) return [];

  const entries = (await db.getAll('assetCacheEntries') as AssetCacheEntry[])
    .filter((entry) => entry.cached && !entry.pinned && now - entry.lastAccessedAt >= ASSET_CACHE_MIN_AGE_MS)
    .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt);
  const evicted: string[] = [];
  const target = limit * ASSET_CACHE_TARGET_RATIO;
  for (const entry of entries) {
    if (usage <= target) break;
    await db.delete('assetVariants', [entry.assetId, 'original']);
    await db.delete('assetVariants', [entry.assetId, 'thumbnail']);
    await db.delete('assetVariants', [entry.assetId, 'preview']);
    await db.delete('assetCacheEntries', entry.assetId);
    usage -= entry.bytes;
    evicted.push(entry.assetId);
  }
  return evicted;
}

export async function saveAsset(asset: MediaAsset | PersistedAsset) {
  await saveAssets([asset]);
}

export async function saveAssets(assets: Array<MediaAsset | PersistedAsset>) {
  const db = await database;
  const transaction = db.transaction(['assetMeta', 'assetVariants', 'assetCacheEntries', 'legacyAssets'], 'readwrite');
  const metaStore = transaction.objectStore('assetMeta');
  const variantStore = transaction.objectStore('assetVariants');
  const cacheStore = transaction.objectStore('assetCacheEntries');
  const legacyStore = transaction.objectStore('legacyAssets');
  const done = transaction.done;
  await Promise.all([
    ...assets.flatMap((asset) => [
      metaStore.put(assetMetadata(asset)),
      variantStore.put({ assetId: asset.id, variant: 'original', blob: asset.originalBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'thumbnail', blob: asset.thumbnailBlob } satisfies PersistedAssetVariant),
      variantStore.put({ assetId: asset.id, variant: 'preview', blob: asset.previewBlob } satisfies PersistedAssetVariant),
      cacheStore.put(cacheEntryFor(asset)),
      legacyStore.delete(asset.id),
    ]),
  ]);
  await done;
  void evictAssetCache().catch(() => undefined);
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
    if (!original || !thumbnail || !preview) {
      const legacy = await db.get('legacyAssets', assetId) as PersistedAsset | undefined;
      if (legacy) await saveAsset(legacy);
      return legacy;
    }
    void touchAssetCache(assetId);
    return { ...metadata, originalBlob: original.blob, thumbnailBlob: thumbnail.blob, previewBlob: preview.blob } satisfies PersistedAsset;
  }
  const legacy = await db.get('legacyAssets', assetId) as PersistedAsset | undefined;
  if (legacy) await saveAsset(legacy);
  return legacy;
}

export async function getAssetVariant(assetId: string, variant: AssetVariant) {
  const db = await database;
  const record = await db.get('assetVariants', [assetId, variant]) as PersistedAssetVariant | undefined;
  if (record) {
    void touchAssetCache(assetId);
    return record.blob;
  }
  const legacy = await db.get('legacyAssets', assetId) as PersistedAsset | undefined;
  if (!legacy) return undefined;
  await saveAsset(legacy);
  return variant === 'original' ? legacy.originalBlob : variant === 'thumbnail' ? legacy.thumbnailBlob : legacy.previewBlob;
}

export async function getAssetMetadata(assetId: string) {
  const db = await database;
  const metadata = await db.get('assetMeta', assetId) as AssetMetadata | undefined;
  if (metadata) return metadata;
  const legacy = await db.get('legacyAssets', assetId) as PersistedAsset | undefined;
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
  if (!ids.length) return [];
  const db = await database;
  const transaction = db.transaction(['assetMeta', 'assetVariants'], 'readonly');
  const done = transaction.done;
  const [metadata, variants] = await Promise.all([
    Promise.all(ids.map((assetId) => transaction.objectStore('assetMeta').get(assetId) as Promise<AssetMetadata | undefined>)),
    Promise.all(ids.flatMap((assetId) => (['original', 'thumbnail', 'preview'] as AssetVariant[]).map((variant) =>
      transaction.objectStore('assetVariants').get([assetId, variant]) as Promise<PersistedAssetVariant | undefined>))),
  ]);
  await done;
  const variantsByKey = new Map(variants.filter((record): record is PersistedAssetVariant => Boolean(record))
    .map((record) => [`${record.assetId}:${record.variant}`, record.blob]));
  const records: PersistedAsset[] = [];
  for (const [index, meta] of metadata.entries()) {
    if (!meta) continue;
    const assetId = ids[index];
    const originalBlob = variantsByKey.get(`${assetId}:original`);
    const thumbnailBlob = variantsByKey.get(`${assetId}:thumbnail`);
    const previewBlob = variantsByKey.get(`${assetId}:preview`);
    if (!originalBlob || !thumbnailBlob || !previewBlob) continue;
    records.push({ ...meta, originalBlob, thumbnailBlob, previewBlob });
    void touchAssetCache(assetId);
  }
  const missingLegacy = ids.filter((assetId) => !metadata.find((meta) => meta?.id === assetId));
  records.push(...await Promise.all(missingLegacy.map(getAsset))
    .then((items) => items.filter((asset): asset is PersistedAsset => Boolean(asset))));
  return records;
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

/** 按 id 重命名项目（项目切换列表里的重命名，不需要先把项目加载成当前项目）。 */
export async function renameProject(projectId: string, title: string) {
  await apiJson(`/projects/${encodeURIComponent(projectId)}`, { method: 'PATCH', body: JSON.stringify({ title }) });
}

export async function loadProject(projectId: string): Promise<ProjectDocumentV2 | undefined> {
  try {
    const p = await apiJson<{ id: string; title: string }>(`/projects/${encodeURIComponent(projectId)}`);
    const now = nowMs();
    const canvases = (await apiJson<Array<{ id: string }>>(
      `/projects/${encodeURIComponent(p.id)}/canvases`)).map((canvas) => canvas.id);
    return { schemaVersion: 2, id: p.id, title: p.title, canvases, createdAt: now, updatedAt: now };
  } catch { return undefined; }
}

// 懒迁移：旧 IndexedDB 项目（id=浏览器生成的 UUID）→ 建后端 ai分镜 项目 + 画布元数据，重映射画布 projectId。
const BACKEND_ID_RE = /^[0-9a-f]{32}$/;

async function migrateLegacyAssets() {
  const db = await database;
  const legacyAssets = await db.getAll('legacyAssets') as PersistedAsset[];
  for (const asset of legacyAssets) {
    const transaction = db.transaction(['legacyAssets', 'assetMeta', 'assetVariants', 'assetCacheEntries'], 'readwrite');
    const done = transaction.done;
    await Promise.all([
      transaction.objectStore('assetMeta').put(assetMetadata(asset)),
      transaction.objectStore('assetVariants').put({ assetId: asset.id, variant: 'original', blob: asset.originalBlob } satisfies PersistedAssetVariant),
      transaction.objectStore('assetVariants').put({ assetId: asset.id, variant: 'thumbnail', blob: asset.thumbnailBlob } satisfies PersistedAssetVariant),
      transaction.objectStore('assetVariants').put({ assetId: asset.id, variant: 'preview', blob: asset.previewBlob } satisfies PersistedAssetVariant),
      transaction.objectStore('assetCacheEntries').put(cacheEntryFor(asset)),
      transaction.objectStore('legacyAssets').delete(asset.id),
    ]);
    await done;
  }
}

async function ensureBackendProjects() {
  const db = await database;
  const legacy = (await db.getAll('legacyProjects')) as LegacyProjectDocument[];
  if (!legacy.length) return;
  for (const p of legacy) {
    if (BACKEND_ID_RE.test(p.id)) {
      await db.delete('legacyProjects', p.id);
      continue;
    }
    try {
      const migration = p.migration ?? { updatedAt: Date.now(), canvasIdMap: {} };
      const backendProjectId = migration.backendProjectId ?? (
        await apiJson<{ id: string }>('/projects', { method: 'POST', body: JSON.stringify({ title: p.title }) })
      ).id;
      if (!migration.backendProjectId) {
        migration.backendProjectId = backendProjectId;
        migration.updatedAt = Date.now();
        await db.put('legacyProjects', { ...p, migration });
      }
      const projectCanvases = await db.getAllFromIndex('canvases', 'by-projectId', p.id) as CanvasDocumentV1[];
      for (const c of projectCanvases) {
        const existingId = migration.canvasIdMap?.[c.id];
        const canvasId = existingId ?? (
          await apiJson<{ id: string }>(`/projects/${encodeURIComponent(backendProjectId)}/canvases`, {
            method: 'POST',
            body: JSON.stringify({ title: c.title }),
          })
        ).id;
        if (!existingId) {
          migration.canvasIdMap = { ...(migration.canvasIdMap ?? {}), [c.id]: canvasId };
          migration.updatedAt = Date.now();
          await db.put('legacyProjects', { ...p, migration });
        }
        const migrated = { ...c, id: canvasId, projectId: backendProjectId, revision: 0 };
        await saveCanvas(migrated);
        if (canvasId !== c.id) await db.delete('canvases', c.id);
        for (const assetId of projectAssetIds(c.nodes)) {
          const meta = await db.get('assetMeta', assetId) as AssetMetadata | undefined;
          if (meta) await db.put('assetMeta', { ...meta, projectId: backendProjectId, canvasId });
        }
      }
      await db.delete('legacyProjects', p.id);
    } catch { /* 迁移失败不阻断（后端不可达/无 token 时跳过，下次再试） */ }
  }
}

export async function migrateLegacyStorage() {
  await migrateLegacyAssets();
  await ensureBackendProjects();
}

export async function listProjects(): Promise<ProjectSummary[]> {
  await migrateLegacyStorage();
  const projects = await apiJson<Array<{
    id: string; title: string; canvasCount?: number; nodeCount?: number; assetCount?: number;
  }>>('/projects');
  return projects.map((p) => {
    const now = nowMs();
    return {
      id: p.id,
      title: p.title,
      createdAt: now,
      updatedAt: now,
      canvasCount: p.canvasCount ?? 0,
      nodeCount: p.nodeCount ?? 0,
      assetCount: p.assetCount ?? 0,
    };
  });
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

// ── 画布：元数据(id/projectId/title)落后端 morrow_canvases，节点图(nodes/edges/viewport)落后端 morrow_canvas_graphs ──
export async function createCanvas(projectId: string, title = '画布'): Promise<CanvasDocumentV1> {
  const meta = await apiJson<{ id: string; projectId: string; title: string }>(`/projects/${encodeURIComponent(projectId)}/canvases`, { method: 'POST', body: JSON.stringify({ title }) });
  const now = nowMs();
  const canvas: CanvasDocumentV1 = { schemaVersion: 1, id: meta.id, projectId, title: meta.title || title, nodes: [], edges: [], viewport: { x: 180, y: 90, zoom: .7 }, generationRecords: [], executions: [], revision: 0, createdAt: now, updatedAt: now };
  await (await database).put('canvases', canvas); // 本地缓存（可离线读）
  return canvas;
}

interface CanvasGraphPayload {
  schemaVersion: 2;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  viewport: Viewport;
  generationRecords?: CanvasDocumentV1['generationRecords'];
  executions?: CanvasDocumentV1['executions'];
  migrationReport?: CanvasDocumentV1['migrationReport'];
}

interface CanvasGraphDto {
  graph: CanvasGraphPayload;
  revision: number;
  updatedAt?: string;
}

/** 提取图载荷：nodes/edges/viewport（去 schemaVersion/id 等元数据）。 */
function graphPayloadOf(canvas: CanvasDocumentV1): CanvasGraphPayload {
  return {
    schemaVersion: 2,
    nodes: canvas.nodes,
    edges: canvas.edges,
    viewport: canvas.viewport,
    generationRecords: canvas.generationRecords ?? [],
    executions: canvas.executions ?? [],
    migrationReport: canvas.migrationReport,
  };
}

/** 用后端图载荷回填 CanvasDocumentV1（保留本地元数据，覆写图 + revision）。 */
function applyGraph(canvas: CanvasDocumentV1, dto: CanvasGraphDto): CanvasDocumentV1 {
  return {
    ...canvas,
    nodes: dto.graph?.nodes ?? [],
    edges: dto.graph?.edges ?? [],
    viewport: dto.graph?.viewport ?? canvas.viewport,
    generationRecords: dto.graph?.generationRecords ?? canvas.generationRecords ?? [],
    executions: dto.graph?.executions ?? canvas.executions ?? [],
    migrationReport: dto.graph?.migrationReport ?? canvas.migrationReport,
    revision: dto.revision ?? canvas.revision ?? 0,
    updatedAt: nowMs(),
  };
}

function normalizeCanvasGraph(canvas: CanvasDocumentV1): CanvasDocumentV1 {
  const legacyKinds = new Set(['prompt', 'sequence', 'imageGen', 'videoGen', 'storyboard', 'storyboardGen']);
  const legacyGenerationPorts = new Set(['first', 'last', 'source', 'images']);
  const generatorNodeIds = new Set(
    canvas.nodes
      .filter((node) => mergedGenerationKind(String(node.data.kind)) !== undefined)
      .map((node) => node.id),
  );
  const needsMigration = canvas.nodes.some((node) =>
    node.type !== node.data.kind
    || legacyKinds.has(String(node.data.kind))
    || (mergedGenerationKind(String(node.data.kind)) !== undefined && mergedGenerationKind(String(node.data.kind)) !== node.data.kind))
    || canvas.edges.some((edge) => !edge.sourceHandle || !edge.targetHandle || edge.sourceHandle === 'right' || edge.targetHandle === 'left'
      // 合并前的生成节点端口（首帧/尾帧/源图/多图）要重写到 reference
      || (legacyGenerationPorts.has(String(edge.targetHandle)) && generatorNodeIds.has(edge.target)));
  if (!needsMigration) return { ...canvas, generationRecords: canvas.generationRecords ?? [], executions: canvas.executions ?? [] };
  const migrated = migrateCanvasGraphV1ToV2({
    schemaVersion: canvas.schemaVersion,
    nodes: canvas.nodes as Parameters<typeof migrateCanvasGraphV1ToV2>[0]['nodes'],
    edges: canvas.edges as Parameters<typeof migrateCanvasGraphV1ToV2>[0]['edges'],
    viewport: canvas.viewport,
    generationRecords: canvas.generationRecords,
  });
  return {
    ...canvas,
    nodes: migrated.nodes as CanvasNode[],
    edges: migrated.edges as CanvasEdge[],
    generationRecords: migrated.generationRecords,
    executions: canvas.executions ?? [],
    migrationReport: migrated.migrationReport,
  };
}

export async function saveCanvas(canvas: CanvasDocumentV1): Promise<CanvasDocumentV1> {
  const now = nowMs();
  const baseRevision = canvas.revision ?? 0;
  // 标题走后端元数据（失败不阻断图入库）
  await apiJson(`/canvases/${encodeURIComponent(canvas.id)}`, { method: 'PATCH', body: JSON.stringify({ title: canvas.title }) }).catch(() => undefined);
  // 图入库：乐观锁 CAS（baseRevision 不匹配抛 40901，message 携带当前 revision）
  const dto = await apiJson<CanvasGraphDto>(`/canvases/${encodeURIComponent(canvas.id)}/graph`, {
    method: 'PUT',
    body: JSON.stringify({ graph: graphPayloadOf(canvas), baseRevision }),
  });
  const saved = applyGraph(canvas, dto);
  await (await database).put('canvases', saved); // 本地缓存回写
  return saved;
}

export async function loadCanvas(canvasId: string, projectId?: string): Promise<CanvasDocumentV1 | undefined> {
  const local = await (await database).get('canvases', canvasId) as CanvasDocumentV1 | undefined;
  try {
    // 后端图先读：成功则以库为准覆写本地元数据
    const dto = await apiJson<CanvasGraphDto>(`/canvases/${encodeURIComponent(canvasId)}/graph`);
    const backendHasContent = (dto.graph?.nodes?.length ?? 0) > 0 || (dto.graph?.edges?.length ?? 0) > 0;
    const localHasContent = (local?.nodes?.length ?? 0) > 0 || (local?.edges?.length ?? 0) > 0;
    // 后端为空但本地有内容（迁移/离线缓存）→ 信任本地，防后端空图覆盖勿丢数据
    if (!backendHasContent && localHasContent) return local ? normalizeCanvasGraph(local) : undefined;
    // 后端记录存在但图仍为空（例如 Agent 刚创建的新画布）→ 返回空画布，不能当成不存在。
    if (!local && !backendHasContent) return emptyCanvas(canvasId, projectId);
    return normalizeCanvasGraph(applyGraph(local ?? emptyCanvas(canvasId, projectId), dto));
  } catch (error) {
    // 401/403/404 是确定的鉴权或归属结果，不能拿本地缓存冒充后端数据，
    // 否则跨账号画布会继续自动保存并向画布服务反复发送 403。
    if (isResolvedHttpError(error)) throw error;
    // 网络不可达或上游 5xx → 降级本地缓存
    return local ? normalizeCanvasGraph(local) : undefined;
  }
}

function isResolvedHttpError(error: unknown): boolean {
  if (!error || typeof error !== 'object' || !('status' in error)) return false;
  const status = (error as { status?: unknown }).status;
  return typeof status === 'number' && status < 500;
}

function emptyCanvas(canvasId: string, projectId = ''): CanvasDocumentV1 {
  const now = nowMs();
  return { schemaVersion: 1, id: canvasId, projectId, title: '画布', nodes: [], edges: [], viewport: { x: 180, y: 90, zoom: .7 }, generationRecords: [], executions: [], revision: 0, createdAt: now, updatedAt: now };
}

export async function listCanvases(projectId: string): Promise<CanvasSummary[]> {
  const metas = await apiJson<Array<{
    id: string; projectId: string; title: string; nodeCount?: number; assetCount?: number;
    coverAssetId?: string | null;
  }>>(`/projects/${encodeURIComponent(projectId)}/canvases`);
  const now = nowMs();
  return metas.map((m) => ({
    id: m.id,
    projectId,
    title: m.title,
    createdAt: now,
    updatedAt: now,
    nodeCount: m.nodeCount ?? 0,
    assetCount: m.assetCount ?? 0,
    coverAssetId: m.coverAssetId ?? null,
  } satisfies CanvasSummary));
}

/** 画布内最早放入的图片资产 id（本地缓存索引），供封面在图未上云时兜底。 */
export async function firstCanvasImageAssetId(canvasId: string): Promise<string | undefined> {
  const db = await database;
  const metas = await db.getAllFromIndex('assetMeta', 'by-canvasId', canvasId) as AssetMetadata[];
  return metas
    .filter((meta) => meta.kind === 'image')
    .sort((left, right) => left.createdAt - right.createdAt)[0]?.id;
}

export async function deleteCanvas(canvasId: string) {
  await apiJson(`/canvases/${encodeURIComponent(canvasId)}`, { method: 'DELETE' });
  await (await database).delete('canvases', canvasId);
}

export async function saveImportedProject(project: ProjectDocumentV2, canvases: CanvasDocumentV1[], assets: PersistedAsset[]) {
  const createdProject = await apiJson<{ id: string; title?: string }>('/projects', {
    method: 'POST',
    body: JSON.stringify({ title: project.title }),
  });
  await saveAssets(assets);
  const migratedCanvases: CanvasDocumentV1[] = [];
  for (const canvas of canvases) {
    const meta = await apiJson<{ id: string; title?: string }>(`/projects/${encodeURIComponent(createdProject.id)}/canvases`, {
      method: 'POST',
      body: JSON.stringify({ title: canvas.title }),
    });
    const migrated = { ...canvas, id: meta.id, projectId: createdProject.id, revision: 0 };
    const saved = await saveCanvas(migrated);
    migratedCanvases.push(saved);

    const db = await database;
    for (const assetId of projectAssetIds(canvas.nodes)) {
      const metadata = await db.get('assetMeta', assetId) as AssetMetadata | undefined;
      if (metadata) await db.put('assetMeta', { ...metadata, projectId: createdProject.id, canvasId: meta.id });
    }
  }
  return {
    project: {
      ...project,
      id: createdProject.id,
      title: createdProject.title || project.title,
      canvases: migratedCanvases.map((canvas) => canvas.id),
    } satisfies ProjectDocumentV2,
    canvases: migratedCanvases,
  };
}

export async function collectAssetGarbage(protectedAssetIds: Iterable<string> = []) {
  const db = await database;
  const [canvases, entries, assetKeys, legacyAssetKeys] = await Promise.all([
    db.getAll('canvases') as Promise<CanvasDocumentV1[]>,
    db.getAll('libraryEntries') as Promise<Array<{ assetId: string; savedAt: number }>>,
    db.getAllKeys('assetMeta') as Promise<IDBValidKey[]>,
    db.getAllKeys('legacyAssets') as Promise<IDBValidKey[]>,
  ]);
  const referenced = new Set(protectedAssetIds);
  canvases.forEach((canvas) => canvasAssetIds(canvas).forEach((id) => referenced.add(id)));
  entries.forEach((entry) => referenced.add(entry.assetId));

  // Only referenced lineage records are read, so GC never materializes every asset Blob.
  const queue = [...referenced];
  while (queue.length) {
    const parentId = (await getAssetMetadata(queue.pop()!))?.derivedFrom;
    if (parentId && !referenced.has(parentId)) { referenced.add(parentId); queue.push(parentId); }
  }

  const garbage = [...new Set([...assetKeys, ...legacyAssetKeys].map(String))].filter((assetId) => !referenced.has(assetId));
  for (let offset = 0; offset < garbage.length; offset += 100) {
    const transaction = db.transaction(['legacyAssets', 'assetMeta', 'assetVariants', 'assetCacheEntries'], 'readwrite');
    const done = transaction.done;
    await Promise.all(garbage.slice(offset, offset + 100).flatMap((assetId) => [
      transaction.objectStore('legacyAssets').delete(assetId),
      transaction.objectStore('assetMeta').delete(assetId),
      transaction.objectStore('assetVariants').delete([assetId, 'original']),
      transaction.objectStore('assetVariants').delete([assetId, 'thumbnail']),
      transaction.objectStore('assetVariants').delete([assetId, 'preview']),
      transaction.objectStore('assetCacheEntries').delete(assetId),
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
  const transaction = db.transaction(['assetMeta', 'assetCacheEntries', 'libraryEntries'], 'readwrite');
  const done = transaction.done;
  await transaction.objectStore('assetMeta').put({ ...assetMetadata(current), libraryState: 'global' });
  const cacheEntry = await transaction.objectStore('assetCacheEntries').get(assetId) as AssetCacheEntry | undefined;
  await transaction.objectStore('assetCacheEntries').put({
    ...(cacheEntry ?? cacheEntryFor(current, true)),
    bytes: Number(cacheEntry?.bytes) || assetCacheBytes(current) || current.bytes,
    pinned: true,
    lastAccessedAt: Date.now(),
  });
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
  const metas = await db.getAllFromIndex('assetMeta', 'by-projectId', projectId) as AssetMetadata[];
  const ids = metas.map((meta) => meta.id);
  return getAssets(ids);
}

// 全局总资产库：所有 libraryState === 'global' 的资产（含其来源项目信息）。
export async function listGlobalAssets() {
  const db = await database;
  const metas = await db.getAllFromIndex('assetMeta', 'by-libraryState', 'global') as AssetMetadata[];
  const ids = metas.map((meta) => meta.id);
  return getAssets(ids);
}

export async function setSetting<T>(key: string, value: T) {
  await (await database).put('settings', value, key);
}

export async function getSetting<T>(key: string) {
  return (await database).get('settings', key) as Promise<T | undefined>;
}

export async function putGenerationQueueItem(item: GenerationQueueItem) {
  await (await database).put('generationQueue', item);
}

export async function deleteGenerationQueueItem(id: string) {
  await (await database).delete('generationQueue', id);
}

export async function listGenerationQueueItems(projectId?: string, canvasId?: string) {
  const db = await database;
  if (projectId && canvasId) {
    return db.getAllFromIndex('generationQueue', 'by-project-canvas', [projectId, canvasId]) as Promise<GenerationQueueItem[]>;
  }
  if (projectId) {
    return db.getAllFromIndex('generationQueue', 'by-projectId', projectId) as Promise<GenerationQueueItem[]>;
  }
  return db.getAll('generationQueue') as Promise<GenerationQueueItem[]>;
}

export async function cleanupGenerationQueue(retentionMs = GENERATION_QUEUE_RETENTION_MS) {
  const db = await database;
  const cutoff = Date.now() - retentionMs;
  const stale = (await db.getAllFromIndex('generationQueue', 'by-updatedAt', IDBKeyRange.upperBound(cutoff))) as GenerationQueueItem[];
  const removed = stale.filter((item) => ['terminal', 'cancelled', 'interrupted'].includes(item.status));
  const transaction = db.transaction('generationQueue', 'readwrite');
  await Promise.all(removed.map((item) => transaction.store.delete(item.id)));
  await transaction.done;
  return removed.map((item) => item.id);
}

export async function clearLocalData() {
  const db = await database;
  const transaction = db.transaction(['legacyProjects', 'legacyAssets', 'canvases', 'assetMeta', 'assetVariants', 'assetCacheEntries', 'libraryEntries', 'settings', 'generationQueue'], 'readwrite');
  const done = transaction.done;
  await Promise.all([
    transaction.objectStore('legacyProjects').clear(),
    transaction.objectStore('legacyAssets').clear(),
    transaction.objectStore('canvases').clear(),
    transaction.objectStore('assetMeta').clear(),
    transaction.objectStore('assetVariants').clear(),
    transaction.objectStore('assetCacheEntries').clear(),
    transaction.objectStore('libraryEntries').clear(),
    transaction.objectStore('settings').clear(),
    transaction.objectStore('generationQueue').clear(),
  ]);
  await done;
}

export const ASSET_GC_DEBOUNCE_MS = 2_000;
export const ASSET_GC_IDLE_TIMEOUT_MS = 5_000;

let assetGcTimer: ReturnType<typeof setTimeout> | null = null;
let assetGcIdle: number | null = null;
let scheduledAssetIds = new Set<string>();

const runScheduledAssetGarbageCollection = async () => {
  const protectedIds = [...scheduledAssetIds];
  scheduledAssetIds = new Set();
  assetGcTimer = null;
  assetGcIdle = null;
  await collectAssetGarbage(protectedIds);
};

const scheduleIdleAssetGarbageCollection = () => {
  const idleWindow = globalThis as typeof globalThis & {
    requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
  };
  if (idleWindow.requestIdleCallback) {
    assetGcIdle = idleWindow.requestIdleCallback(
      () => void runScheduledAssetGarbageCollection().catch(() => undefined),
      { timeout: ASSET_GC_IDLE_TIMEOUT_MS },
    );
    return;
  }
  assetGcTimer = setTimeout(
    () => void runScheduledAssetGarbageCollection().catch(() => undefined),
    ASSET_GC_IDLE_TIMEOUT_MS,
  );
};

export function scheduleAssetGarbageCollection(protectedAssetIds: Iterable<string> = []) {
  for (const assetId of protectedAssetIds) scheduledAssetIds.add(assetId);
  if (assetGcTimer !== null || assetGcIdle !== null) return;
  assetGcTimer = setTimeout(() => {
    assetGcTimer = null;
    scheduleIdleAssetGarbageCollection();
  }, ASSET_GC_DEBOUNCE_MS);
}

export async function flushScheduledAssetGarbageCollection() {
  if (assetGcTimer !== null) {
    clearTimeout(assetGcTimer);
    assetGcTimer = null;
  }
  if (assetGcIdle !== null) assetGcIdle = null;
  await runScheduledAssetGarbageCollection();
}

export function projectAssetIds(nodes: CanvasNode[]) {
  return new Set(nodes.flatMap((node) => node.data.assetId ? [node.data.assetId] : []));
}

export function canvasAssetIds(canvas: CanvasDocumentV1) {
  const ids = projectAssetIds(canvas.nodes);
  for (const record of canvas.generationRecords ?? []) {
    record.materializedAssetIds.forEach((id) => ids.add(id));
    record.sources.forEach((source) => { if (source.assetId) ids.add(source.assetId); });
  }
  return ids;
}

export function cloneProjectGraph(nodes: CanvasNode[], edges: CanvasEdge[]) {
  return { nodes: structuredClone(nodes), edges: structuredClone(edges) };
}
