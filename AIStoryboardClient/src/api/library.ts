import client from './client';
import type { ApiResponse } from './auth';

export type AssetScope = 'PROJECT' | 'GLOBAL';
export type AssetType = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'AI_ASSET' | 'GROUP';
export type AssetStatus = 'ACTIVE' | 'TRASHED' | 'PURGE_PENDING' | 'PURGE_BLOCKED' | 'PURGED';
export type TagMode = 'ALL' | 'ANY';
export type MediaType = 'IMAGE' | 'VIDEO' | 'AUDIO';

export interface LibraryTag {
  id: string;
  name: string;
  color?: string | null;
  source?: string;
}

export interface ProjectLinkRef {
  projectId: string;
  createdAt: string;
}

export interface LibraryAsset {
  id: string;
  ownerUserId: string;
  scope: AssetScope;
  ownerProjectId: string | null;
  type: AssetType;
  aiKind?: 'CHARACTER' | 'PROP' | 'SCENE' | null;
  name: string;
  description: string | null;
  aiDescription: string | null;
  manualOverride: boolean;
  status: AssetStatus;
  coverUrl: string | null;
  version: number;
  tags: LibraryTag[];
  projects: ProjectLinkRef[];
  sourceType: string | null;
  sourceId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LibraryMedia {
  id: string;
  blobId: string;
  type: MediaType;
  url: string;
  contentUrl: string;
  objectKey: string;
  fileName: string | null;
  relativePath: string | null;
  mimeType: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
  durationSeconds: number | null;
  sortOrder: number;
  role: 'REFERENCE' | 'FIRST_FRAME' | 'LAST_FRAME';
  primary: boolean;
  constraintText: string | null;
}

export interface LibraryAssetDetail {
  asset: LibraryAsset;
  media: LibraryMedia[];
  linkedProjects: ProjectLinkRef[];
}

export interface LibraryPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface AssetGroupSnapshot {
  snapshotId: string;
  groupId: string;
  groupVersion: number;
  contentHash: string;
  target: string;
  items: Array<{
    assetId: string;
    mediaId: string;
    blobId: string;
    objectKey: string;
    contentUrl: string;
    type: MediaType;
    role: string;
    sortOrder: number;
  }>;
  createdAt: string;
}

export interface ImportEntry {
  itemKey: string;
  relativePath: string;
  fileName: string;
  sizeBytes: number;
  mimeType: string;
}

export interface ImportScanResult {
  jobId: string;
  confirmationToken: string;
  imageCount: number;
  totalBytes: number;
  ignoredCount: number;
  expiresAt: string;
}

export const libraryApi = {
  listAssets: (params: {
    scope?: AssetScope;
    projectId?: string;
    q?: string;
    type?: AssetType;
    tagIds?: string[];
    tagMode?: TagMode;
    deleted?: boolean;
    page?: number;
    size?: number;
  }) => client.get<ApiResponse<LibraryPage<LibraryAsset>>>('/library/assets', { params }),

  getAsset: (id: string) => client.get<ApiResponse<LibraryAssetDetail>>(`/library/assets/${id}`),

  updateAsset: (
    id: string,
    data: { name?: string; description?: string; expectedVersion: number; manualOverride?: boolean },
  ) => client.put<ApiResponse<LibraryAsset>>(`/library/assets/${id}`, data),

  createAsset: (data: {
    ownerProjectId?: string | null;
    type: AssetType;
    aiKind?: 'CHARACTER' | 'PROP' | 'SCENE';
    name: string;
    description?: string;
    sourceType?: string;
  }) => client.post<ApiResponse<LibraryAsset>>('/library/assets', data, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  }),

  promote: (id: string, expectedVersion: number) =>
    client.post<ApiResponse<LibraryAsset>>(`/library/assets/${id}/promote`, { expectedVersion }),

  linkProject: (id: string, projectId: string) =>
    client.post(`/library/assets/${id}/projects`, { projectId }),

  trash: (id: string, expectedVersion: number) =>
    client.delete<ApiResponse<LibraryAsset>>(`/library/assets/${id}`, { params: { expectedVersion } }),

  restore: (id: string) => client.post<ApiResponse<LibraryAsset>>(`/library/assets/${id}/restore`),

  purge: (id: string) => client.delete(`/library/assets/${id}/purge`),

  listTags: () => client.get<ApiResponse<LibraryTag[]>>('/library/tags'),

  createTag: (data: { name: string; color?: string }) =>
    client.post<ApiResponse<LibraryTag>>('/library/tags', data),

  replaceTags: (assetId: string, tagIds: string[]) =>
    client.put(`/library/assets/${assetId}/tags`, { tagIds }),

  createGroup: (data: {
    ownerProjectId?: string | null;
    scope: AssetScope;
    name: string;
    description?: string;
  }) => client.post<ApiResponse<LibraryAsset>>('/library/groups', data, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  }),

  getGroup: (groupId: string) =>
    client.get<ApiResponse<{
      groupId: string;
      name: string;
      description: string | null;
      version: number;
      media: LibraryMedia[];
    }>>(`/library/groups/${groupId}`),

  replaceGroupItems: (
    groupId: string,
    expectedVersion: number,
    items: Array<{ mediaId: string; sortOrder: number; role: 'REFERENCE' | 'FIRST_FRAME' | 'LAST_FRAME' }>,
  ) => client.put(`/library/groups/${groupId}/items`, { items }, { params: { expectedVersion } }),

  createSnapshot: (groupId: string, target = 'VIDEO') =>
    client.post<ApiResponse<AssetGroupSnapshot>>(`/library/groups/${groupId}/snapshots`, {
      target,
      maxImages: 9,
      maxVideos: 3,
      maxAudios: 3,
    }),

  registerGeneratedOutput: (data: {
    producer: string;
    projectId?: string | null;
    generationRecordId: string;
    resultIndex: number;
    groupId?: string | null;
    resultAssetId?: string | null;
    resultUrl: string;
    mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO';
    prompt?: string | null;
    model?: string | null;
    metadata?: Record<string, unknown>;
  }) => client.post<ApiResponse<LibraryAsset>>('/library/generation-outputs', data),

  uploadDirect: (
    file: File,
    options: { projectId?: string; scope: AssetScope; name?: string; relativePath?: string },
  ) => {
    const form = new FormData();
    form.append('file', file);
    if (options.projectId) form.append('projectId', options.projectId);
    form.append('scope', options.scope);
    if (options.name) form.append('name', options.name);
    if (options.relativePath) form.append('relativePath', options.relativePath);
    return client.post<ApiResponse<LibraryAsset>>('/library/uploads/direct', form, {
      headers: {
        'Content-Type': 'multipart/form-data',
        'Idempotency-Key': crypto.randomUUID(),
      },
      timeout: 600_000,
    });
  },

  scanImport: (data: {
    projectId?: string | null;
    scope: AssetScope;
    entries: ImportEntry[];
  }) => client.post<ApiResponse<ImportScanResult>>('/library/imports/scan', data),

  uploadImportItem: (
    jobId: string,
    itemKey: string,
    confirmationToken: string,
    file: File,
  ) => {
    const form = new FormData();
    form.append('file', file);
    return client.post<ApiResponse<{ status: string }>>(`/library/imports/${jobId}/items`, form, {
      params: { itemKey, confirmationToken },
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600_000,
    });
  },
};
