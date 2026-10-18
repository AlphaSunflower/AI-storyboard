import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SceneResponse } from '../../api/projects';
import { SceneOutlineRow, sceneParamLine, sceneSummary, statusTone } from './SceneOutlineRow';

function scene(overrides: Partial<SceneResponse> = {}): SceneResponse {
  return {
    id: 'scene-1',
    projectId: 'project-1',
    conversationId: 'conversation-1',
    sceneNumber: 3,
    scriptContent: '风衣男子冒雨站在废弃仓库大门外，观察警戒。门轴轻响，他停了半拍。',
    imagePrompt: '',
    videoPrompt: '',
    negativePrompt: '',
    cameraMovement: '推近',
    shotType: '全景',
    soundDesign: '',
    aiModel: '',
    videoResolution: '',
    duration: 5,
    imageUrl: '',
    videoUrl: '',
    imageStatus: 'pending',
    videoStatus: 'pending',
    videoTaskId: '',
    imageUrls: '',
    imageModel: '',
    imageSize: '',
    imageQuality: '',
    imageN: 1,
    videoModel: '',
    videoAspectRatio: '',
    createdAt: '',
    updatedAt: '',
    ...overrides,
  } as SceneResponse;
}

describe('SceneOutlineRow', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('summarises only the first sentence so one screen fits 20+ shots', () => {
    // 只取首句，且不超过 40 字 + 省略号
    expect(sceneSummary(scene())).toBe('风衣男子冒雨站在废弃仓库大门外，观察警戒');

    const long = scene({ scriptContent: '甲'.repeat(60) });
    expect(sceneSummary(long)).toHaveLength(41);
    expect(sceneSummary(long).endsWith('…')).toBe(true);

    expect(sceneSummary(scene({ scriptContent: '   ' }))).toBe('（未填写画面）');
  });

  it('renders parameter line only for values that exist', () => {
    expect(sceneParamLine(scene())).toBe('全景 · 推近 · 5s');
    expect(sceneParamLine(scene({ shotType: '', cameraMovement: '', duration: 0 }))).toBe('');
  });

  it('maps generation status to three tones plus idle', () => {
    expect(statusTone('completed')).toBe('ok');
    expect(statusTone('generating')).toBe('run');
    expect(statusTone('failed')).toBe('fail');
    expect(statusTone('pending')).toBe('idle');
    expect(statusTone(undefined)).toBe('idle');
  });

  it('selects the shot when the row is clicked', () => {
    const onSelect = vi.fn();
    render(<SceneOutlineRow scene={scene()} isSelected={false} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole('button', { name: /分镜 3/ }));

    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it('exposes the row as current for the selected shot', () => {
    render(<SceneOutlineRow scene={scene()} isSelected onSelect={() => {}} />);

    expect(screen.getByRole('button', { name: /分镜 3/ }).getAttribute('aria-current'))
      .toBe('true');
  });

  it('shows the thumbnail when the shot already has an image', () => {
    render(
      <SceneOutlineRow
        scene={scene({ imageUrl: '/api/files/images/a.png', imageStatus: 'completed' })}
        isSelected={false}
        onSelect={() => {}}
      />,
    );

    expect(document.querySelector('img')?.getAttribute('src')).toBe('/api/files/images/a.png');
    expect(screen.getByLabelText('图片状态').getAttribute('title')).toContain('completed');
  });

  it('marks a shot as stale when its media predates the latest draft edit', () => {
    render(
      <SceneOutlineRow
        scene={scene({ imageUrl: '/api/files/images/a.png', imageStatus: 'completed', stale: true })}
        isSelected={false}
        onSelect={() => {}}
      />,
    );

    expect(screen.getByText('过期')).toBeTruthy();
  });

  it('does not show the stale badge for fresh media', () => {
    render(
      <SceneOutlineRow
        scene={scene({ imageUrl: '/api/files/images/a.png', imageStatus: 'completed', stale: false })}
        isSelected={false}
        onSelect={() => {}}
      />,
    );

    expect(screen.queryByText('过期')).toBeNull();
  });
});
