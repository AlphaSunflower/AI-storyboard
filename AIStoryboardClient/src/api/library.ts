import client from './client';
import type { ApiResponse } from './auth';

export type AssetScope = 'PROJECT' | 'GLOBAL';
export type AssetType = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'OTHER' | 'AI_ASSET' | 'GROUP';
export type AssetStatus = 'ACTIVE' | 'TRASHED' | 'PURGE_PENDING' | 'PURGE_BLOCKED' | 'PURGED';
export type MetadataState = 'NONE' | 'AI_GENERATING' | 'AI_GENERATED' | 'AI_FAILED' | 'EDITED';
export type TagMode = 'ALL' | 'ANY';
export type MediaType = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'OTHER';
export type ImportMode = 'ASSETS' | 'GROUP' | 'TREE';

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
  metadataState: MetadataState;
  status: AssetStatus;
  coverUrl: string | null;
  version: number;
  tags: LibraryTag[];
  projects: ProjectLinkRef[];
  folderId: string | null;
  folderPath: string[];
  sourceType: string | null;
  sourceId: string | null;
  /** 产出素材所属对话；上传素材为 null。 */
  conversationId?: string | null;
  /** 产出素材来源镜头；同一对话 + 同一镜头的多次生成归为一组版本。 */
  sceneId?: string | null;
  /** 挑片星级 0–5。 */
  rating?: number;
  /** 挑片旗标：pick / reject。 */
  flag?: 'pick' | 'reject' | null;
  /** 挑片色标。 */
  colorLabel?: 'red' | 'yellow' | 'green' | 'blue' | 'purple' | null;
  createdAt: string;
  updatedAt: string;
}

/** 「产出素材」按对话聚合出来的虚拟文件夹。 */
export interface OutputConversationSummary {
  conversationId: string | null;
  assetCount: number;
  imageCount: number;
  videoCount: number;
  documentCount: number;
  coverUrl: string | null;
  previewUrls: string[];
  lastAssetName: string | null;
  lastGeneratedAt: string | null;
}

/** 资产组使用侧信息：成员数、快照次数、最近一次快照时间。 */
export interface LibraryGroupInsight {
  groupId: string;
  version: number;
  mediaCount: number;
  snapshotCount: number;
  lastSnapshotAt: string | null;
}

/** 同一份文件（sha256 相同）被多个素材引用时的重复组。 */
export interface DuplicateGroup {
  blobId: string;
  sha256: string;
  mimeType: string;
  sizeBytes: number;
  scope: AssetScope;
  ownerProjectId: string;
  assets: LibraryAsset[];
}

export type AssetFlag = 'pick' | 'reject';
export type AssetColorLabel = 'red' | 'yellow' | 'green' | 'blue' | 'purple';

/** 可保存的筛选视图。 */
export interface LibrarySavedView {
  id: string;
  ownerUserId: string;
  scope: AssetScope;
  ownerProjectId: string;
  name: string;
  filters: Record<string, unknown>;
  sort: Record<string, unknown>;
  viewMode: 'folders' | 'tree';
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
}

/** 素材的一条使用位置。 */
export interface UsageRef {
  id: string;
  title: string;
  detail: string | null;
}

export interface AssetUsage {
  assetId: string;
  total: number;
  scenes: UsageRef[];
  conversations: UsageRef[];
  groups: UsageRef[];
  canvases: UsageRef[];
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

export interface LibraryAssetGroupDetail {
  groupId: string;
  name: string;
  description: string | null;
  version: number;
  media: LibraryGroupMediaItem[];
}

export interface LibraryGroupMediaItem {
  media: LibraryMedia;
  asset: LibraryAsset | null;
  assetId: string | null;
  assetName: string | null;
  assetDescription: string | null;
  assetVersion: number;
  assetTags: LibraryTag[];
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
  fileCount: number;
  totalBytes: number;
  ignoredCount: number;
  groupId: string | null;
  expiresAt: string;
}

export interface ImportJobStatus {
  id: string;
  status: 'CREATED' | 'UPLOADING' | 'COMPLETED' | 'PARTIAL';
  projectId: string | null;
  scope: AssetScope;
  mode: ImportMode;
  groupId: string | null;
  totalCount: number;
  processedCount: number;
  succeededCount: number;
  duplicateCount: number;
  failedCount: number;
  errorMessage: string | null;
  updatedAt: string;
}

export interface AnalysisTaskStatus {
  assetId: string;
  status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'DEAD';
  errorMessage: string | null;
  updatedAt: string;
}

export interface AnalysisRequestItem {
  assetId: string;
  name: string;
}

export interface AnalysisRequestResult {
  items: AnalysisRequestItem[];
  skipped: number;
}

export interface LibraryFolderNode {
  id: string;
  parentId: string | null;
  name: string;
  scope: AssetScope;
  ownerProjectId: string | null;
  directAssetCount: number;
  descendantAssetCount: number;
  version: number;
}

export interface LibraryFolderTree {
  scope: AssetScope;
  ownerProjectId: string | null;
  folders: LibraryFolderNode[];
  unfiledAssetCount: number;
}

export interface BulkAssetOperationResult {
  updatedIds: string[];
  skipped: Array<{ assetId: string; reason: string }>;
}

export const libraryApi = {
  listAssets: (params: {
    scope?: AssetScope;
    projectId?: string;
    folderId?: string;
    q?: string;
    type?: AssetType;
    tagIds?: string[];
    tagMode?: TagMode;
    deleted?: boolean;
    /** 项目范围内同时纳入该账号的全局素材。 */
    includeGlobal?: boolean;
    /** 只返回系统生成回写的产出素材。 */
    origin?: 'GENERATED';
    /** 产出素材看板：按对话过滤；UNASSIGNED = 未归属任何对话的生成结果。 */
    conversationId?: string;
    /** 挑片标记过滤。 */
    minRating?: number;
    flag?: AssetFlag;
    colorLabel?: AssetColorLabel;
    page?: number;
    size?: number;
  }) => client.get<ApiResponse<LibraryPage<LibraryAsset>>>('/library/assets', {
    params,
    // Spring 的 List<String> 绑定要求 tagIds=a&tagIds=b；axios 默认会写成 tagIds[]=a。
    paramsSerializer: { indexes: null },
  }),

  /** 「产出素材」根视图：一个对话一个虚拟文件夹。 */
  listOutputConversations: (params: {
    projectId: string;
    q?: string;
    type?: AssetType;
    tagIds?: string[];
    tagMode?: TagMode;
    minRating?: number;
    flag?: AssetFlag;
    colorLabel?: AssetColorLabel;
  }) => client.get<ApiResponse<OutputConversationSummary[]>>('/library/outputs/conversations', {
    params,
    paramsSerializer: { indexes: null },
  }),

  getAsset: (id: string) => client.get<ApiResponse<LibraryAssetDetail>>(`/library/assets/${id}`),

  /** 素材使用位置：分镜 / 对话 / 资产组 / 画布。 */
  getAssetUsage: (assetId: string) =>
    client.get<ApiResponse<AssetUsage>>(`/library/assets/${assetId}/usage`),

  /** 未被分镜、资产组、画布引用的素材（不含资产组本身）。 */
  listUnusedAssets: (params: { projectId: string; page?: number; size?: number }) =>
    client.get<ApiResponse<LibraryPage<LibraryAsset>>>('/library/assets/unused', { params }),

  /** 智能搜索：关键词 / 主色 / 找相似。 */
  searchAssets: (params: {
    projectId: string;
    q?: string;
    color?: string;
    similarTo?: string;
    limit?: number;
  }) => client.get<ApiResponse<LibraryAsset[]>>('/library/assets/search', { params }),

  getMedia: (mediaId: string) =>
    client.get<ApiResponse<LibraryMedia>>(`/library/media/${encodeURIComponent(mediaId)}/metadata`),

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

  bulkTrash: (assetIds: string[]) =>
    client.post<ApiResponse<BulkAssetOperationResult>>('/library/assets/bulk/trash', { assetIds }),

  bulkPromote: (assetIds: string[]) =>
    client.post<ApiResponse<BulkAssetOperationResult>>('/library/assets/bulk/promote', { assetIds }),

  duplicateAssets: (data: { assetIds: string[]; folderId?: string | null }) =>
    client.post<ApiResponse<BulkAssetOperationResult>>('/library/assets/bulk/duplicate', data),

  linkProject: (id: string, projectId: string) =>
    client.post(`/library/assets/${id}/projects`, { projectId }),

  trash: (id: string, expectedVersion: number) =>
    client.delete<ApiResponse<LibraryAsset>>(`/library/assets/${id}`, { params: { expectedVersion } }),

  restore: (id: string) => client.post<ApiResponse<LibraryAsset>>(`/library/assets/${id}/restore`),

  purge: (id: string) => client.delete(`/library/assets/${id}/purge`),

  listTags: () => client.get<ApiResponse<LibraryTag[]>>('/library/tags'),

  createTag: (data: { name: string; color?: string }) =>
    client.post<ApiResponse<LibraryTag>>('/library/tags', data),

  deleteTag: (tagId: string) =>
    client.delete(`/library/tags/${tagId}`),

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

  createGroupFromAssets: (data: {
    ownerProjectId: string;
    name: string;
    assetIds: string[];
  }) => client.post<ApiResponse<LibraryAsset>>('/library/groups/from-assets', data, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  }),

  getFolderTree: (params: { scope: AssetScope; projectId?: string | null }) =>
    client.get<ApiResponse<LibraryFolderTree>>('/library/folders/tree', { params }),

  createFolder: (data: {
    scope: AssetScope;
    ownerProjectId?: string | null;
    parentId?: string | null;
    name: string;
  }) => client.post<ApiResponse<LibraryFolderNode>>('/library/folders', data),

  renameFolder: (folderId: string, data: { name: string; expectedVersion: number }) =>
    client.put<ApiResponse<LibraryFolderNode>>(`/library/folders/${folderId}`, data),

  deleteFolder: (folderId: string, expectedVersion: number) =>
    client.delete(`/library/folders/${folderId}`, { params: { expectedVersion } }),

  moveFolder: (
    folderId: string,
    data: { parentId: string | null; expectedVersion: number },
  ) => client.put(`/library/folders/${folderId}/parent`, data),

  copyFolders: (data: { folderIds: string[]; parentId?: string | null }) =>
    client.post<ApiResponse<BulkAssetOperationResult>>('/library/folders/bulk/copy', data),

  moveAssetsToFolder: (
    folderId: string,
    data: { scope: AssetScope; ownerProjectId?: string | null; assetIds: string[] },
  ) => client.put(`/library/folders/${folderId}/assets`, data),

  getGroup: (groupId: string) =>
    client.get<ApiResponse<LibraryAssetGroupDetail>>(`/library/groups/${groupId}`),

  /** 资产组卡片/检查器：成员数、快照次数、最近快照时间。 */
  listGroupInsights: (projectId: string) =>
    client.get<ApiResponse<LibraryGroupInsight[]>>('/library/group-insights', {
      params: { projectId },
    }),

  /** 重复素材：同一 sha256 被多个素材引用的分组。 */
  listDuplicates: (params: { projectId: string; page?: number; size?: number }) =>
    client.get<ApiResponse<LibraryPage<DuplicateGroup>>>('/library/duplicates', { params }),

  /** 合并重复素材：保留 survivor，其余素材的引用迁移后进回收站。 */
  mergeDuplicates: (data: { survivorAssetId: string; duplicateAssetIds: string[] }) =>
    client.post<ApiResponse<LibraryAsset>>('/library/duplicates/merge', data),

  /** 单张素材挑片标记：整组覆盖，null 即清除。 */
  markAsset: (assetId: string, data: {
    rating?: number;
    flag?: AssetFlag | null;
    colorLabel?: AssetColorLabel | null;
  }) => client.post<ApiResponse<LibraryAsset>>(`/library/assets/${assetId}/mark`, data),

  /** 批量挑片标记。 */
  bulkMarkAssets: (data: {
    assetIds: string[];
    rating?: number;
    flag?: AssetFlag | null;
    colorLabel?: AssetColorLabel | null;
  }) => client.post<ApiResponse<BulkAssetOperationResult>>('/library/assets/bulk/mark', data),

  listSavedViews: (projectId: string) =>
    client.get<ApiResponse<LibrarySavedView[]>>('/library/views', { params: { projectId } }),

  createSavedView: (data: {
    projectId: string;
    name: string;
    filters: Record<string, unknown>;
    sort?: Record<string, unknown>;
    viewMode?: 'folders' | 'tree';
    pinned?: boolean;
  }) => client.post<ApiResponse<LibrarySavedView>>('/library/views', data),

  updateSavedView: (viewId: string, data: {
    name?: string;
    filters?: Record<string, unknown>;
    sort?: Record<string, unknown>;
    viewMode?: 'folders' | 'tree';
    pinned?: boolean;
  }) => client.put<ApiResponse<LibrarySavedView>>(`/library/views/${viewId}`, data),

  deleteSavedView: (viewId: string) => client.delete(`/library/views/${viewId}`),

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
    mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'OTHER';
    prompt?: string | null;
    model?: string | null;
    metadata?: Record<string, unknown>;
  }) => client.post<ApiResponse<LibraryAsset>>('/library/generation-outputs', data),

  uploadDirect: (
    file: File,
    options: { projectId?: string; scope: AssetScope; name?: string; relativePath?: string },
    onUploadProgress?: (progress: number) => void,
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
      onUploadProgress: onUploadProgress
        ? (event) => {
          const total = event.total || 0;
          onUploadProgress(total > 0 ? Math.round(event.loaded / total * 100) : 0);
        }
        : undefined,
      timeout: 600_000,
    });
  },

  scanImport: (data: {
    projectId?: string | null;
    scope: AssetScope;
    mode: ImportMode;
    groupName?: string;
    entries: ImportEntry[];
  }) => client.post<ApiResponse<ImportScanResult>>('/library/imports/scan', data),

  getImportJob: (jobId: string) =>
    client.get<ApiResponse<ImportJobStatus>>(`/library/imports/${jobId}`),

  uploadImportItem: (
    jobId: string,
    itemKey: string,
    confirmationToken: string,
    file: File,
    onUploadProgress?: (progress: number) => void,
  ) => {
    const form = new FormData();
    form.append('file', file);
    return client.post<ApiResponse<{ status: string; asset: LibraryAsset | null; message: string | null }>>(
      `/library/imports/${jobId}/items`,
      form,
      {
      params: { itemKey, confirmationToken },
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onUploadProgress
        ? (event) => {
          const total = event.total || 0;
          onUploadProgress(total > 0 ? Math.round(event.loaded / total * 100) : 0);
        }
        : undefined,
      timeout: 600_000,
      },
    );
  },

  getAnalysisTasks: (assetIds: string[]) =>
    client.get<ApiResponse<AnalysisTaskStatus[]>>('/library/assets/analysis-tasks', {
      params: { assetIds: assetIds.join(',') },
    }),

  /** 用户主动触发：为选中的图片素材排队 AI 元数据完善。 */
  requestAnalysis: (assetIds: string[]) =>
    client.post<ApiResponse<AnalysisRequestResult>>('/library/assets/analysis', { assetIds }),

  /** 用户主动触发：为目录（含子目录）内的图片素材排队 AI 元数据完善。 */
  requestFolderAnalysis: (folderId: string, params: { scope: AssetScope; projectId?: string }) =>
    client.post<ApiResponse<AnalysisRequestResult>>(
      `/library/folders/${encodeURIComponent(folderId)}/analysis`,
      null,
      { params },
    ),
};
