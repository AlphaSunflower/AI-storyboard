import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { libraryApi, type LibraryAsset } from '../../api/library';
import { AssetGroupPickerModal } from './AssetGroupPickerModal';

const group = (id: string, name: string, scope: 'PROJECT' | 'GLOBAL'): LibraryAsset => ({
  id,
  ownerUserId: 'u1',
  scope,
  ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
  type: 'GROUP',
  aiKind: 'CHARACTER',
  name,
  description: null,
  aiDescription: null,
  manualOverride: false,
  metadataState: 'NONE',
  status: 'ACTIVE',
  coverUrl: null,
  version: 1,
  tags: [],
  projects: [],
  folderId: null,
  folderPath: [],
  sourceType: null,
  sourceId: null,
  createdAt: '2026-09-21T00:00:00Z',
  updatedAt: '2026-09-21T00:00:00Z',
});

describe('AssetGroupPickerModal', () => {
  afterEach(() => cleanup());

  it('merges project and global groups with an all filter', async () => {
    const list = vi.spyOn(libraryApi, 'listAssets')
      .mockResolvedValueOnce({
        data: { code: 0, message: 'ok', data: { records: [group('g1', '主角组', 'PROJECT')], total: 1, page: 1, size: 100 } },
      } as never)
      .mockResolvedValueOnce({
        data: { code: 0, message: 'ok', data: { records: [group('g2', '全局场景', 'GLOBAL')], total: 1, page: 1, size: 100 } },
      } as never);
    const confirm = vi.fn();

    render(
      <AssetGroupPickerModal
        open
        projectId="p1"
        onClose={() => undefined}
        onConfirm={confirm}
      />,
    );

    expect(screen.queryByText('全局库')).toBeNull();
    expect(screen.getByRole('button', { name: '全部' })).toBeTruthy();
    fireEvent.click(await screen.findByText('主角组'));
    fireEvent.click(await screen.findByText('全局场景'));
    fireEvent.click(screen.getByRole('button', { name: '确认关联' }));

    await waitFor(() => expect(confirm).toHaveBeenCalledWith(['g1', 'g2']));
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ scope: 'PROJECT', type: 'GROUP' }));
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ scope: 'GLOBAL', type: 'GROUP' }));
  });
});
