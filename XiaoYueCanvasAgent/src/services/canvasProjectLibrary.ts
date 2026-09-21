import { apiJson } from './morrowApi';
import type { CanvasAssetDescriptor } from './canvasAssets';

export interface CanvasLibraryMedia {
  id: string;
  blobId: string;
  type: 'IMAGE' | 'VIDEO' | 'AUDIO';
  url: string;
  contentUrl: string;
  objectKey: string;
  fileName: string;
  relativePath: string | null;
  mimeType: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
  durationSeconds: number | null;
  sortOrder: number;
  role: string;
  primary: boolean;
  constraintText: string | null;
}

export interface CanvasLibraryAsset {
  id: string;
  scope: 'PROJECT' | 'GLOBAL';
  ownerProjectId: string | null;
  type: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'AI_ASSET' | 'GROUP';
  aiKind?: 'CHARACTER' | 'PROP' | 'SCENE' | null;
  name: string;
  description: string | null;
  coverUrl: string | null;
  sourceType: string | null;
  sourceId: string | null;
  status: string;
}

export interface CanvasLibraryFolder {
  id: string;
  parentId: string | null;
  name: string;
  scope: 'PROJECT' | 'GLOBAL';
  ownerProjectId: string | null;
  directAssetCount: number;
  descendantAssetCount: number;
  version: number;
}

export interface CanvasLibraryTree {
  scope: 'PROJECT' | 'GLOBAL';
  ownerProjectId: string | null;
  folders: CanvasLibraryFolder[];
  unfiledAssetCount: number;
}

export interface CanvasProjectLibraryResponse {
  projectTree: CanvasLibraryTree | null;
  globalTree: CanvasLibraryTree;
  assets: CanvasLibraryAsset[];
  groups: CanvasLibraryAsset[];
}

export async function loadCanvasProjectLibrary(params: {
  projectId: string;
  scope?: 'PROJECT' | 'GLOBAL';
  folderId?: string;
  type?: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'GROUP';
}): Promise<CanvasProjectLibraryResponse> {
  const query = new URLSearchParams({ projectId: params.projectId });
  if (params.scope) query.set('scope', params.scope);
  if (params.folderId) query.set('folderId', params.folderId);
  if (params.type) query.set('type', params.type);
  return apiJson<CanvasProjectLibraryResponse>(`/project-library?${query.toString()}`);
}

interface RawCanvasAsset {
  id: string;
  canvasId: string;
  type: 'image' | 'video' | 'audio';
  mimeType: string;
  objectUrl: string;
  objectKey: string;
  libraryAssetId?: string;
  librarySyncState?: 'pending' | 'ready' | 'failed';
  producerNodeId?: string;
  createdAt?: string;
}

function normalizeImportedCanvasAsset(asset: RawCanvasAsset): CanvasAssetDescriptor {
  return {
    id: asset.id,
    canvasId: asset.canvasId,
    type: asset.type,
    mimeType: asset.mimeType,
    url: asset.objectUrl,
    name: asset.objectUrl.substring(asset.objectUrl.lastIndexOf('/') + 1),
    libraryAssetId: asset.libraryAssetId,
    librarySyncState: asset.librarySyncState,
    producerNodeId: asset.producerNodeId,
    createdAt: asset.createdAt,
  };
}

export async function importCanvasLibraryAsset(
  canvasId: string,
  libraryAssetId: string,
  producerNodeId = '',
): Promise<CanvasAssetDescriptor> {
  const asset = await apiJson<RawCanvasAsset>('/project-library/import', {
    method: 'POST',
    body: JSON.stringify({ canvasId, libraryAssetId, producerNodeId }),
  });
  return normalizeImportedCanvasAsset(asset);
}

export async function importCanvasLibraryGroup(
  canvasId: string,
  libraryAssetId: string,
  producerNodeId = '',
): Promise<CanvasAssetDescriptor[]> {
  const assets = await apiJson<RawCanvasAsset[]>('/project-library/import-group', {
    method: 'POST',
    body: JSON.stringify({ canvasId, libraryAssetId, producerNodeId }),
  });
  return assets.map(normalizeImportedCanvasAsset);
}

export function libraryFolderChildren(
  response: CanvasProjectLibraryResponse | null,
  scope: 'PROJECT' | 'GLOBAL' | null,
  parentId: string | null,
): CanvasLibraryFolder[] {
  if (!response) return [];
  if (scope === 'GLOBAL') {
    return response.globalTree.folders.filter((folder) => folder.parentId === parentId);
  }
  if (scope === 'PROJECT') {
    return (response.projectTree?.folders ?? []).filter((folder) => folder.parentId === parentId);
  }
  return [
    ...(response.projectTree?.folders ?? []).filter((folder) => folder.parentId === null),
    ...response.globalTree.folders.filter((folder) => folder.parentId === null),
  ];
}
