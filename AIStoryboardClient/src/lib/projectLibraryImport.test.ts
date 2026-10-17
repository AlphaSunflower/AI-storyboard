import { beforeEach, describe, expect, it, vi } from 'vitest';
import { libraryApi, type LibraryAsset } from '../api/library';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import { importMediaToProjectLibrary, findProjectLibraryImport, projectLibraryGenerationRecordId } from './projectLibraryImport';

const asset: LibraryAsset = {
  id: 'asset-1',
  ownerUserId: 'user-1',
  scope: 'PROJECT',
  ownerProjectId: 'project-1',
  type: 'IMAGE',
  name: '分镜图.png',
  description: null,
  aiDescription: null,
  manualOverride: false,
  metadataState: 'AI_GENERATING',
  status: 'ACTIVE',
  coverUrl: '/api/files/images/storyboard.png',
  version: 1,
  tags: [],
  projects: [],
  folderId: null,
  folderPath: [],
  sourceType: 'generation',
  sourceId: 'record-1',
  createdAt: '',
  updatedAt: '',
};

describe('importMediaToProjectLibrary', () => {
  beforeEach(() => {
    useLibraryTaskStore.setState({ tasks: [], panelOpen: false });
    vi.restoreAllMocks();
  });

  it('registers generated media in the target project and reports completion', async () => {
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: { code: 0, message: '', data: asset },
    } as never);

    const result = await importMediaToProjectLibrary({
      projectId: 'project-1',
      producer: 'moon-chat',
      url: '/api/files/images/storyboard.png',
      mediaType: 'IMAGE',
      label: '分镜图.png',
    });

    expect(result).toEqual(asset);
    const payload = register.mock.calls[0]?.[0];
    expect(payload).toMatchObject({
      projectId: 'project-1',
      producer: 'moon-chat',
      resultIndex: 0,
      resultUrl: '/api/files/images/storyboard.png',
      mediaType: 'IMAGE',
    });
    expect(payload).not.toHaveProperty('prompt');
    expect(useLibraryTaskStore.getState().panelOpen).toBe(true);
    expect(useLibraryTaskStore.getState().tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ status: 'completed', assetId: 'asset-1' }),
    ]));
    expect(useLibraryTaskStore.getState().tasks.some((task) => task.kind === 'analysis')).toBe(false);
  });

  it('uses the shared idempotency key so import and lookup agree', async () => {
    const recordId = await projectLibraryGenerationRecordId('moon-chat', '/api/files/images/storyboard.png');
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: { code: 0, message: '', data: asset },
    } as never);

    await importMediaToProjectLibrary({
      projectId: 'project-1',
      producer: 'moon-chat',
      url: '/api/files/images/storyboard.png',
      mediaType: 'IMAGE',
      generationRecordId: recordId,
    });

    expect(register.mock.calls[0]?.[0]?.generationRecordId).toBe(recordId);
  });
});

describe('findProjectLibraryImport', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function page(records: Partial<LibraryAsset>[]) {
    return {
      data: { code: 0, message: '', data: { records, total: records.length, page: 1, size: 200 } },
    } as never;
  }

  it('recognises an asset that was registered from the same media url', async () => {
    const recordId = await projectLibraryGenerationRecordId('moon-chat', '/api/files/images/a.png');
    vi.spyOn(libraryApi, 'listAssets').mockResolvedValue(
      page([{ id: 'library-1', sourceId: recordId }]),
    );

    const found = await findProjectLibraryImport({
      projectId: 'project-1',
      url: '/api/files/images/a.png',
    });

    expect(found?.id).toBe('library-1');
  });

  it('recognises an agent asset that was registered by asset id', async () => {
    vi.spyOn(libraryApi, 'listAssets').mockResolvedValue(
      page([{ id: 'library-2', sourceId: 'agent-asset-9' }]),
    );

    const found = await findProjectLibraryImport({
      projectId: 'project-1',
      url: '/api/files/videos/clip.mp4',
      assetId: 'agent-asset-9',
    });

    expect(found?.id).toBe('library-2');
  });

  it('returns null when nothing matches, so the import entry stays offered', async () => {
    vi.spyOn(libraryApi, 'listAssets').mockResolvedValue(page([{ id: 'library-3', sourceId: 'other' }]));

    expect(await findProjectLibraryImport({ projectId: 'project-1', url: '/api/files/images/a.png' }))
      .toBeNull();
  });

  it('treats a failing lookup as "not imported" instead of blocking the entry', async () => {
    vi.spyOn(libraryApi, 'listAssets').mockRejectedValue(new Error('boom'));

    expect(await findProjectLibraryImport({ projectId: 'project-1', url: '/api/files/images/a.png' }))
      .toBeNull();
  });
});
