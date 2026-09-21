// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssetLibrary } from './AssetLibrary';
import { loadCanvasProjectLibrary } from '../services/canvasProjectLibrary';

vi.mock('../services/canvasProjectLibrary', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/canvasProjectLibrary')>();
  return { ...actual, loadCanvasProjectLibrary: vi.fn() };
});

const asset = (
  id: string,
  name: string,
  sourceType: string,
  scope: 'PROJECT' | 'GLOBAL',
) => ({
  id,
  scope,
  ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
  type: 'IMAGE' as const,
  aiKind: null,
  name,
  description: null,
  coverUrl: null,
  sourceType,
  sourceId: null,
  status: 'ACTIVE',
});

describe('AssetLibrary', () => {
  afterEach(() => {
    cleanup();
    vi.mocked(loadCanvasProjectLibrary).mockReset();
  });

  it('shows one project library containing folders, canvas assets, global assets, and asset sets', async () => {
    vi.mocked(loadCanvasProjectLibrary).mockResolvedValue({
      projectTree: {
        scope: 'PROJECT',
        ownerProjectId: 'p1',
        folders: [{
          id: 'project-folder',
          parentId: null,
          name: '项目参考',
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          directAssetCount: 0,
          descendantAssetCount: 1,
          version: 1,
        }],
        unfiledAssetCount: 1,
      },
      globalTree: {
        scope: 'GLOBAL',
        ownerProjectId: null,
        folders: [{
          id: 'global-folder',
          parentId: null,
          name: '全局参考',
          scope: 'GLOBAL',
          ownerProjectId: null,
          directAssetCount: 0,
          descendantAssetCount: 1,
          version: 1,
        }],
        unfiledAssetCount: 1,
      },
      assets: [
        asset('a1', '画布成品', 'canvas-generation', 'PROJECT'),
        asset('a2', '共享素材', 'upload', 'GLOBAL'),
      ],
      groups: [{
        id: 'g1',
        scope: 'GLOBAL',
        ownerProjectId: null,
        type: 'GROUP',
        aiKind: 'SCENE',
        name: '场景集',
        description: null,
        coverUrl: null,
        sourceType: null,
        sourceId: null,
        status: 'ACTIVE',
      }],
    });

    render(<AssetLibrary projectId="p1" />);

    expect(await screen.findByText('项目参考')).toBeTruthy();
    expect(screen.getByText('全局参考')).toBeTruthy();
    expect(screen.getByText('画布成品')).toBeTruthy();
    expect(screen.getByText('共享素材')).toBeTruthy();
    expect(screen.getByText('场景集')).toBeTruthy();
    expect(screen.getByRole('button', { name: '全部' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '项目画布素材' }));
    expect(screen.queryByText('共享素材')).toBeNull();
    expect(screen.queryByText('场景集')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '资产集' }));
    await waitFor(() => expect(screen.getByText('场景集')).toBeTruthy());
    expect(screen.queryByText('画布成品')).toBeNull();
    expect(screen.getAllByTitle('双击打开文件夹').length).toBeGreaterThan(0);
  });
});
