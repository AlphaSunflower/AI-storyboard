// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CanvasToolRail, type CanvasToolRailProps } from './CanvasToolRail';

function renderRail(overrides: Partial<CanvasToolRailProps> = {}) {
  const props: CanvasToolRailProps = {
    onAddNode: vi.fn(),
    onUpload: vi.fn(),
    onToggleAssets: vi.fn(),
    assetsActive: false,
    ...overrides,
  };
  render(<CanvasToolRail {...props} />);
  return props;
}

describe('CanvasToolRail', () => {
  afterEach(() => cleanup());

  it('把节点库的节点按分类顺序铺成图标，末尾接上传与项目库', () => {
    renderRail();

    const expected = ['文字', '图片', '视频', '分镜拆解', '镜头', '图片生成', '视频生成', '视频合并', '上传', '项目库'];
    expect(screen.getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual(expected);
  });

  it('每个按钮都在 DOM 里带名称文本，悬停展开只切换显隐而不新增节点', () => {
    renderRail();

    // 展开态由 CSS 的 :hover / :focus-within 控制，所以名称必须常驻 DOM 而不是 tooltip 挂载时才出现。
    expect(screen.getAllByText('分镜拆解')).toHaveLength(1);
    expect(screen.getAllByText('上传')).toHaveLength(1);
    expect(screen.getAllByText('项目库')).toHaveLength(1);
  });

  it('点节点按钮把 kind 交给父级决定落点', () => {
    const props = renderRail();

    fireEvent.click(screen.getByRole('button', { name: '镜头' }));

    expect(props.onAddNode).toHaveBeenCalledWith('shot');
  });

  it('点每个节点按钮都传出自己的 kind', () => {
    const props = renderRail();

    for (const [label, kind] of [['文字', 'text'], ['图片生成', 'imageGeneration'], ['视频合并', 'videoMerge']] as const) {
      fireEvent.click(screen.getByRole('button', { name: label }));
      expect(props.onAddNode).toHaveBeenLastCalledWith(kind);
    }
    expect(props.onAddNode).toHaveBeenCalledTimes(3);
  });

  it('上传与项目库分别走各自的回调', () => {
    const props = renderRail();

    fireEvent.click(screen.getByRole('button', { name: '上传' }));
    fireEvent.click(screen.getByRole('button', { name: '项目库' }));

    expect(props.onUpload).toHaveBeenCalledTimes(1);
    expect(props.onToggleAssets).toHaveBeenCalledTimes(1);
  });

  it('项目库抽屉打开时项目库按钮标记为激活', () => {
    renderRail({ assetsActive: true });

    expect(screen.getByRole('button', { name: '项目库' }).className).toContain('is-active');
  });

  it('不渲染已合并下架或隐藏的旧节点', () => {
    renderRail();

    for (const retired of ['文生图', '图生视频', '批量完善图片', '剧本', '批注']) {
      expect(screen.queryByRole('button', { name: retired })).toBeNull();
    }
  });
});
