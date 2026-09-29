import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from './SceneCard';

const scene = {
  id: 'scene-1',
  projectId: 'project-1',
  conversationId: null,
  sceneNumber: 1,
  scriptContent: '人物推门进入房间',
  imagePrompt: '图片提示词',
  videoPrompt: '视频提示词',
  negativePrompt: '',
  cameraMovement: '推镜',
  shotType: '中景',
  soundDesign: '',
  imageUrl: '',
  videoUrl: '',
  imageStatus: '',
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

describe('SceneCard', () => {
  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => window.setTimeout(callback, 0));
    vi.stubGlobal('cancelAnimationFrame', (handle: number) => window.clearTimeout(handle));
    useProjectStore.setState({
      videoProgress: {},
      unreadScenes: new Set(),
      deleteScene: vi.fn(),
      updateSceneInStore: useProjectStore.getInitialState().updateSceneInStore,
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps prompt editing out of the storyboard list card', () => {
    render(<SceneCard scene={scene} isSelected={false} onSelect={vi.fn()} />);

    expect(screen.queryByText('编辑描述/提示词')).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
  });
});
