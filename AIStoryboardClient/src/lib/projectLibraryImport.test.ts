import { beforeEach, describe, expect, it, vi } from 'vitest';
import { libraryApi, type LibraryAsset } from '../api/library';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import { importMediaToProjectLibrary } from './projectLibraryImport';

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
});
