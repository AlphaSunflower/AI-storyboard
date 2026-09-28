import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UploadMaterialPickerModal } from './UploadMaterialPickerModal';

describe('UploadMaterialPickerModal', () => {
  afterEach(() => cleanup());

  it('hides the standalone asset group entry and explains the merged library flow', () => {
    render(
      <UploadMaterialPickerModal
        open
        onClose={vi.fn()}
        onProjectLibrary={vi.fn()}
        onProjectOutput={vi.fn()}
        onScene={vi.fn()}
      />,
    );

    expect(screen.queryByText('关联资产组')).toBeNull();
    expect(screen.getByText('选择项目库素材或关联资产组，也可直接上传入库')).toBeTruthy();
    expect(screen.getByText('项目库素材')).toBeTruthy();
    expect(screen.getByText('上传项目产出素材')).toBeTruthy();
    expect(screen.getByText('上传分镜')).toBeTruthy();
  });

  it('keeps the standalone asset group entry for legacy callers', () => {
    const onAssetGroup = vi.fn();
    const onClose = vi.fn();
    render(
      <UploadMaterialPickerModal
        open
        onClose={onClose}
        onAssetGroup={onAssetGroup}
        onProjectLibrary={vi.fn()}
        onProjectOutput={vi.fn()}
        onScene={vi.fn()}
      />,
    );

    expect(screen.getByText('选择项目库文件或文件夹，也可直接上传入库')).toBeTruthy();
    fireEvent.click(screen.getByText('关联资产组'));
    expect(onClose).toHaveBeenCalled();
    expect(onAssetGroup).toHaveBeenCalled();
  });
});
