import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { LibraryTaskQueue } from './LibraryTaskQueue';
import { useLibraryTaskStore } from '../../stores/libraryTaskStore';

describe('LibraryTaskQueue', () => {
  beforeEach(() => {
    useLibraryTaskStore.setState({ tasks: [], panelOpen: true });
  });

  it('filters failed tasks and retries one', () => {
    useLibraryTaskStore.setState({
      tasks: [{
        id: 'analysis:a1',
        kind: 'analysis',
        label: '主角.png',
        status: 'failed',
        progress: 0,
        assetId: 'a1',
        error: '模型不可用',
      }],
    });
    const retry = vi.fn();

    render(<LibraryTaskQueue onRetry={retry} />);
    fireEvent.click(screen.getByRole('button', { name: '失败 1' }));
    fireEvent.click(screen.getByRole('button', { name: '重试 主角.png' }));

    expect(retry).toHaveBeenCalledWith(expect.objectContaining({ id: 'analysis:a1' }));
    cleanup();
  });

  it('shows in-flight progress and batch label', () => {
    useLibraryTaskStore.setState({
      tasks: [{
        id: 'upload:1',
        kind: 'upload',
        label: '场景.png',
        status: 'uploading',
        progress: 42,
        batchLabel: '第 1 批',
      }],
    });

    render(<LibraryTaskQueue />);
    expect(screen.getByText('第 1 批')).toBeTruthy();
    expect(screen.getByText('上传中')).toBeTruthy();
    cleanup();
  });
});
