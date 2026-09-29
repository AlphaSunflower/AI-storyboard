import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assetApi } from '../../api/assets';
import { libraryApi } from '../../api/library';
import { sceneApi } from '../../api/scenes';
import { useProjectStore } from '../../stores/projectStore';
import { PreviewPanel } from './PreviewPanel';

describe('PreviewPanel project library import', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    });
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null as never);
    vi.spyOn(assetApi, 'listSceneAssets').mockResolvedValue({
      data: { code: 0, message: '', data: { imageAssets: [], videoAssets: [] } },
    } as never);
    useProjectStore.setState({
      scenes: [{
        id: 'scene-1',
        sceneNumber: 1,
        imageUrl: '/api/files/images/storyboard.png',
        imageStatus: 'completed',
      }] as never,
      selectedSceneId: 'scene-1',
      currentProject: { id: 'project-1', name: '测试项目' } as never,
      sceneRefs: {},
      imageModelOptions: [],
      videoModelOptions: [],
      imageModel: 'image-model',
      videoModel: 'video-model',
      imageSize: '1024x1024',
      imageQuality: 'standard',
      imageN: 1,
      videoPreset: 'standard',
      generatingImage: {},
      generatingVideo: {},
      videoProgress: {},
      fetchSceneRefs: vi.fn(),
      uploadSceneRef: vi.fn(),
      linkSceneRefs: vi.fn(),
      deleteSceneRef: vi.fn(),
      setSceneParams: vi.fn(),
      clearSceneParams: vi.fn(),
      generateImage: vi.fn(),
      generateVideo: vi.fn(),
      pollVideoTask: vi.fn(),
      refreshScenes: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('imports the generated scene image with the toolbar action', async () => {
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          id: 'asset-1',
          ownerUserId: 'user-1',
          scope: 'PROJECT',
          ownerProjectId: 'project-1',
          type: 'IMAGE',
          name: '分镜图片',
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
        },
      },
    } as never);

    render(<PreviewPanel />);
    fireEvent.click(screen.getByRole('button', { name: '导入项目库' }));

    await waitFor(() => {
      expect(register).toHaveBeenCalledWith(expect.objectContaining({
        projectId: 'project-1',
        producer: 'storyboard-editor',
        resultUrl: '/api/files/images/storyboard.png',
        mediaType: 'IMAGE',
      }));
    });
  });

  it('saves the edited image prompt before generating from the detail panel', async () => {
    const update = vi.spyOn(sceneApi, 'update').mockResolvedValue({
      data: {
        data: {
          ...useProjectStore.getState().scenes[0],
          imagePrompt: '新的图片提示词',
        },
      },
    } as never);

    render(<PreviewPanel />);
    fireEvent.change(screen.getByLabelText('图片提示词'), {
      target: { value: '新的图片提示词' },
    });
    fireEvent.click(screen.getByRole('button', { name: '生成图片' }));

    await waitFor(() => {
      expect(update).toHaveBeenCalledWith('scene-1', { imagePrompt: '新的图片提示词' });
    });
    await waitFor(() => {
      expect(useProjectStore.getState().generateImage).toHaveBeenCalledWith(
        'scene-1',
        '新的图片提示词',
        'image-model',
        undefined,
        undefined,
        undefined,
      );
    });
  });
});
