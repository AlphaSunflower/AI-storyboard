import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sceneApi } from '../../api/scenes';
import { useProjectStore } from '../../stores/projectStore';
import { MobileScenePreview } from './MobileScenePreview';

const scene = {
  id: 'scene-1',
  projectId: 'project-1',
  conversationId: null,
  sceneNumber: 1,
  scriptContent: '人物推门进入房间',
  imagePrompt: '旧图片提示词',
  videoPrompt: '旧视频提示词',
  negativePrompt: '',
  cameraMovement: '推镜',
  shotType: '中景',
  soundDesign: '',
  imageUrl: '/api/files/images/scene-1.png',
  videoUrl: '',
  imageStatus: 'completed',
  videoStatus: '',
  videoTaskId: '',
  imageUrls: '',
  imageModel: '',
  imageSize: '',
  imageQuality: '',
  imageN: 0,
  videoModel: '',
  videoAspectRatio: '',
  videoResolution: '',
  duration: 0,
};

describe('MobileScenePreview', () => {
  const generateImage = vi.fn().mockResolvedValue('/api/files/images/new.png');
  const generateVideo = vi.fn().mockResolvedValue('task-1');
  const setSceneParams = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    });
    useProjectStore.setState({
      scenes: [scene],
      selectedSceneId: scene.id,
      currentProject: { id: 'project-1', name: '测试项目' } as never,
      imageModelOptions: [{ value: 'image-model', label: '图片模型' }],
      videoModelOptions: [{ value: 'video-model', label: '视频模型' }],
      imageModel: 'image-model',
      videoModel: 'video-model',
      imageN: 1,
      videoPreset: '8s-16:9',
      generatingImage: {},
      generatingVideo: {},
      generateImage,
      generateVideo,
      setSceneParams,
      updateSceneInStore: useProjectStore.getInitialState().updateSceneInStore,
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    generateImage.mockClear();
    generateVideo.mockClear();
    setSceneParams.mockClear();
  });

  it('shows prompt editing and image or video parameters without a hidden toggle', () => {
    render(<MobileScenePreview onBack={vi.fn()} />);

    expect(screen.getByLabelText('图片提示词')).toBeTruthy();
    expect(screen.getByText('模型')).toBeTruthy();
    expect(screen.getByText('尺寸')).toBeTruthy();
    expect(screen.getByText('质量')).toBeTruthy();
    expect(screen.getByText('数量')).toBeTruthy();
    expect(screen.queryByTitle('生成参数')).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: '视频' }));

    expect(screen.getByLabelText('视频提示词')).toBeTruthy();
    expect(screen.getByText('时长')).toBeTruthy();
    expect(screen.getByText('分辨率')).toBeTruthy();
    expect(screen.getByText('画幅')).toBeTruthy();
  });

  it('saves an edited prompt and sends the edited text to generation', async () => {
    const update = vi.spyOn(sceneApi, 'update').mockResolvedValue({ data: { data: scene } } as never);
    render(<MobileScenePreview onBack={vi.fn()} />);

    const prompt = screen.getByLabelText('图片提示词');
    fireEvent.change(prompt, { target: { value: '新的图片提示词' } });
    fireEvent.click(screen.getByRole('button', { name: '重新生成图片' }));

    await waitFor(() => {
      expect(update).toHaveBeenCalledWith(scene.id, { imagePrompt: '新的图片提示词' });
    });
    await waitFor(() => {
      expect(generateImage).toHaveBeenCalledWith(scene.id, '新的图片提示词', 'image-model');
    });
  });
});
