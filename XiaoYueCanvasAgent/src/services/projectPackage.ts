import type { CanvasDocumentV1, CanvasEdge, CanvasNode, PersistedAsset, ProjectDocumentV1, ProjectDocumentV2 } from '../types';
import { canvasAssetIds, getAssetsWithAncestors, listProjects, loadCanvas, loadProject, saveImportedProject } from './projectStorage';
import { unzipInWorker, zipInWorker } from '../utils/archiveWorkerClient';
import { createId } from '../utils/id';

const PACKAGE_VERSION = 3;
const MAX_PACKAGE_BYTES = 256 * 1024 * 1024;
const MAX_FILES = 1000;
const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

interface PackageAssetManifest {
  id: string;
  kind?: PersistedAsset['kind'];
  name: string;
  mimeType: string;
  bytes: number;
  width: number;
  height: number;
  durationSeconds?: number;
  derivedFrom?: string;
  operation?: PersistedAsset['operation'];
  originalPath: string;
  thumbnailPath: string;
  previewPath: string;
  checksums: {
    original: string;
    thumbnail: string;
    preview: string;
  };
}

interface PackageManifest {
  packageVersion: number;
  project: ProjectDocumentV2;
  canvases: CanvasDocumentV1[];
  assets: PackageAssetManifest[];
}

const blobBytes = async (blob: Blob) => new Uint8Array(await blob.arrayBuffer());
const sha256 = async (bytes: Uint8Array) => {
  const copy = new Uint8Array(bytes);
  const digest = await crypto.subtle.digest('SHA-256', copy.buffer);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('');
};

export async function exportProjectPackage(projectId: string) {
  const project = await loadProject(projectId);
  if (!project) throw new Error('项目不存在');
  const canvases = (await Promise.all(project.canvases.map(loadCanvas))).filter((canvas): canvas is CanvasDocumentV1 => Boolean(canvas));
  const assetIds = new Set(canvases.flatMap((canvas) => [...canvasAssetIds(canvas)]));
  const assets = await getAssetsWithAncestors(assetIds);
  const files: Record<string, Uint8Array> = {};
  const assetManifests: PackageAssetManifest[] = [];

  for (const asset of assets) {
    const base = `assets/${asset.id}`;
    const originalPath = `${base}/original`;
    const thumbnailPath = `${base}/thumbnail.webp`;
    const previewPath = `${base}/preview.webp`;
    const [original, thumbnail, preview] = await Promise.all([
      blobBytes(asset.originalBlob), blobBytes(asset.thumbnailBlob), blobBytes(asset.previewBlob),
    ]);
    files[originalPath] = original;
    files[thumbnailPath] = thumbnail;
    files[previewPath] = preview;
    assetManifests.push({
      id: asset.id,
      kind: asset.kind,
      name: asset.name,
      mimeType: asset.mimeType,
      bytes: asset.bytes,
      width: asset.width,
      height: asset.height,
      durationSeconds: asset.durationSeconds,
      derivedFrom: asset.derivedFrom,
      operation: asset.operation,
      originalPath,
      thumbnailPath,
      previewPath,
      checksums: {
        original: await sha256(original),
        thumbnail: await sha256(thumbnail),
        preview: await sha256(preview),
      },
    });
  }

  const manifest: PackageManifest = { packageVersion: PACKAGE_VERSION, project, canvases, assets: assetManifests };
  files['manifest.json'] = textEncoder.encode(JSON.stringify(manifest));
  const archive = await zipInWorker(files, 6);
  return new Blob([archive as Uint8Array<ArrayBuffer>], { type: 'application/x-morrow-project' });
}

const validatePackagePath = (path: string) => !path.includes('..') && !path.startsWith('/') && !path.includes('\\');
const hasAsciiSignature = (bytes: Uint8Array, offset: number, signature: string) => {
  if (bytes.length < offset + signature.length) return false;
  return [...signature].every((character, index) => bytes[offset + index] === character.charCodeAt(0));
};
const matchesImageMime = (bytes: Uint8Array, mimeType: string) => {
  if (mimeType === 'image/png') return bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  if (mimeType === 'image/jpeg') return bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[bytes.length - 2] === 0xff && bytes[bytes.length - 1] === 0xd9;
  return mimeType === 'image/webp' && hasAsciiSignature(bytes, 0, 'RIFF') && hasAsciiSignature(bytes, 8, 'WEBP');
};
const matchesVideoMime = (bytes: Uint8Array, mimeType: string) => {
  if (mimeType === 'video/mp4' || mimeType === 'video/quicktime') return bytes.length > 12 && hasAsciiSignature(bytes, 4, 'ftyp');
  return mimeType === 'video/webm' && bytes.length > 4 && bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3;
};

export async function importProjectPackage(file: File) {
  if (file.size > MAX_PACKAGE_BYTES) throw new Error('项目包超过 256 MB 浏览器安全限制');
  const entries = await unzipInWorker(new Uint8Array(await file.arrayBuffer()));
  const paths = Object.keys(entries);
  if (paths.length > MAX_FILES || paths.some((path) => !validatePackagePath(path))) throw new Error('项目包结构不安全');
  const expandedBytes = Object.values(entries).reduce((total, entry) => total + entry.byteLength, 0);
  if (expandedBytes > MAX_PACKAGE_BYTES) throw new Error('项目包解压后超过 256 MB 浏览器安全限制');
  const manifestBytes = entries['manifest.json'];
  if (!manifestBytes) throw new Error('项目包缺少 manifest.json');
  const manifest = JSON.parse(textDecoder.decode(manifestBytes)) as PackageManifest;

  // 兼容 v1（旧包 project 含单画布图）与 v2（project + canvases）。
  const isV2 = manifest.packageVersion >= 2 && Array.isArray((manifest as { canvases?: unknown }).canvases);
  const legacyProject = manifest.project as unknown as ProjectDocumentV1;
  const sourceCanvases: CanvasDocumentV1[] = isV2
    ? manifest.canvases
    : [{ schemaVersion: 1, id: createId(), projectId: legacyProject.id, title: '默认画布', nodes: legacyProject.nodes, edges: legacyProject.edges, viewport: legacyProject.viewport, createdAt: legacyProject.createdAt, updatedAt: legacyProject.updatedAt }];

  if (!Array.isArray(manifest.assets)) throw new Error('项目包清单损坏');
  const allNodes = sourceCanvases.flatMap((canvas) => canvas.nodes);
  const allEdges = sourceCanvases.flatMap((canvas) => canvas.edges);
  if (new Set(manifest.assets.map((asset) => asset.id)).size !== manifest.assets.length) throw new Error('项目包包含重复素材 ID');
  const allowedMimeTypes = new Set(['image/png', 'image/jpeg', 'image/webp', 'video/mp4', 'video/webm', 'video/quicktime']);
  const assetIds = new Set(manifest.assets.map((asset) => asset.id));
  const nodeIds = new Set(allNodes.map((node) => node.id));
  const supportedNodeKinds = new Set([
    'text', 'image', 'video', 'videoGen', 'script', 'note', 'prompt', 'sequence', 'storyboard', 'storyboardGen', 'imageGen',
    'storyboardPlanner', 'shot', 'textToImage', 'imageToImage', 'batchImageRefine',
    'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo',
  ]);
  if (nodeIds.size !== allNodes.length || allNodes.some((node) => !supportedNodeKinds.has(node.data.kind))) throw new Error('项目包包含无效节点');
  if (allEdges.some((edge) => !nodeIds.has(edge.source) || !nodeIds.has(edge.target))) throw new Error('项目包包含无效连线');
  if (allNodes.some((node) => node.data.assetId && !assetIds.has(node.data.assetId))) throw new Error('项目图引用了缺失素材');

  for (const asset of manifest.assets) {
    if (!allowedMimeTypes.has(asset.mimeType)) throw new Error(`素材 ${asset.name} 的格式不受支持`);
    if (!Number.isSafeInteger(asset.width) || !Number.isSafeInteger(asset.height) || asset.width < 1 || asset.height < 1 || asset.width * asset.height > 64_000_000) throw new Error(`素材 ${asset.name} 的尺寸无效`);
    const original = entries[asset.originalPath]; const thumbnail = entries[asset.thumbnailPath]; const preview = entries[asset.previewPath];
    if (!original || !thumbnail || !preview || !asset.checksums) throw new Error(`素材 ${asset.name} 不完整`);
    if (original.byteLength !== asset.bytes) throw new Error(`素材 ${asset.name} 的文件长度不一致`);
    const validOriginal = asset.mimeType.startsWith('video/') ? matchesVideoMime(original, asset.mimeType) : matchesImageMime(original, asset.mimeType);
    if (!validOriginal || !matchesImageMime(thumbnail, 'image/webp') || !matchesImageMime(preview, 'image/webp')) throw new Error(`素材 ${asset.name} 的 MIME 与内容不一致`);
    const checksums = await Promise.all([sha256(original), sha256(thumbnail), sha256(preview)]);
    if (checksums[0] !== asset.checksums.original || checksums[1] !== asset.checksums.thumbnail || checksums[2] !== asset.checksums.preview) throw new Error(`素材 ${asset.name} 校验失败`);
  }

  const assetIdMap = new Map(manifest.assets.map((asset) => [asset.id, createId()]));
  const nodeIdMap = new Map(allNodes.map((node) => [node.id, `${node.data.kind}-${createId()}`]));
  const assets: PersistedAsset[] = manifest.assets.map((asset) => {
    const original = entries[asset.originalPath];
    const thumbnail = entries[asset.thumbnailPath];
    const preview = entries[asset.previewPath];
    if (!original || !thumbnail || !preview) throw new Error(`素材 ${asset.name} 不完整`);
    return {
      id: assetIdMap.get(asset.id)!,
      kind: asset.kind ?? (asset.mimeType.startsWith('video/') ? 'video' : 'image'),
      name: asset.name,
      mimeType: asset.mimeType,
      bytes: original.byteLength,
      width: asset.width,
      height: asset.height,
      durationSeconds: asset.durationSeconds,
      originalBlob: new Blob([original as Uint8Array<ArrayBuffer>], { type: asset.mimeType }),
      thumbnailBlob: new Blob([thumbnail as Uint8Array<ArrayBuffer>], { type: 'image/webp' }),
      previewBlob: new Blob([preview as Uint8Array<ArrayBuffer>], { type: 'image/webp' }),
      derivedFrom: asset.derivedFrom ? assetIdMap.get(asset.derivedFrom) : undefined,
      operation: asset.operation,
      libraryState: 'project',
      createdAt: Date.now(),
    };
  });

  const remapNode = (node: CanvasNode): CanvasNode => ({
    ...node,
    id: nodeIdMap.get(node.id)!,
    selected: false,
    data: {
      ...node.data,
      assetId: node.data.assetId ? assetIdMap.get(node.data.assetId) : undefined,
      derivedFrom: node.data.derivedFrom ? nodeIdMap.get(node.data.derivedFrom) : undefined,
      annotation: node.data.annotation ? {
        ...node.data.annotation,
        sourceAssetId: assetIdMap.get(node.data.annotation.sourceAssetId) ?? node.data.annotation.sourceAssetId,
      } : undefined,
    },
  });
  const remapEdge = (edge: CanvasEdge): CanvasEdge => ({
    ...edge,
    id: `edge-${createId()}`,
    source: nodeIdMap.get(edge.source)!,
    target: nodeIdMap.get(edge.target)!,
  });
  const remapGenerationRecords = (records: CanvasDocumentV1['generationRecords'] = []) => records.map((record) => ({
    ...record,
    producerNodeId: nodeIdMap.get(record.producerNodeId) ?? record.producerNodeId,
    resultNodeIds: record.resultNodeIds.map((id) => nodeIdMap.get(id) ?? id),
    materializedAssetIds: record.materializedAssetIds.map((id) => assetIdMap.get(id) ?? id),
    sources: record.sources.map((source) => ({
      ...source,
      nodeId: nodeIdMap.get(source.nodeId) ?? source.nodeId,
      assetId: assetIdMap.get(source.assetId) ?? source.assetId,
    })),
  }));

  const now = Date.now();
  const titles = new Set((await listProjects()).map((item) => item.title));
  const titleBase = `${manifest.project.title || 'Untitled'}（导入）`;
  let title = titleBase; let suffix = 2;
  while (titles.has(title)) title = `${titleBase} ${suffix++}`;

  const projectId = createId();
  const canvases: CanvasDocumentV1[] = sourceCanvases.map((canvas, index) => ({
    schemaVersion: 1,
    id: createId(),
    projectId,
    title: canvas.title || (index === 0 ? '默认画布' : `画布 ${index + 1}`),
    nodes: canvas.nodes.map(remapNode),
    edges: canvas.edges.map(remapEdge),
    viewport: canvas.viewport,
    generationRecords: remapGenerationRecords(canvas.generationRecords),
    migrationReport: canvas.migrationReport,
    createdAt: now,
    updatedAt: now,
  }));
  const project: ProjectDocumentV2 = {
    schemaVersion: 2,
    id: projectId,
    title,
    canvases: canvases.map((canvas) => canvas.id),
    createdAt: now,
    updatedAt: now,
  };
  await saveImportedProject(project, canvases, assets);
  return { project, canvases };
}
