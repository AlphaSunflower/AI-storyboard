import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatMentionMenu, mentionItemMatches, type MentionMenuItem } from './ChatMentionMenu';

afterEach(cleanup);

function buildItems(overrides: Partial<MentionMenuItem>[] = []): MentionMenuItem[] {
  const base: MentionMenuItem[] = [
    {
      id: 'source-library', group: 'source', icon: <span>库</span>, title: '项目库文件',
      description: '从项目库选择素材', keywords: '项目库 素材', run: vi.fn(),
    },
    {
      id: 'scene-3', group: 'scene', icon: <span>镜</span>, title: '分镜 3',
      description: '雨夜街头', keywords: '分镜3', run: vi.fn(),
    },
    {
      id: 'conversation-2', group: 'conversation', icon: <span>话</span>, title: '对话《分镜重做》',
      description: '23 条 · 引用对话将在下一步开放', keywords: '对话 会话',
      disabled: true, disabledHint: '引用对话将在下一步（会话摘要）开放', run: vi.fn(),
    },
  ];
  overrides.forEach((patch, index) => Object.assign(base[index], patch));
  return base;
}

describe('mentionItemMatches', () => {
  const [library, scene] = buildItems();

  it('空查询全部命中', () => {
    expect(mentionItemMatches(library, '')).toBe(true);
  });

  it('命中主文案、副文案、关键词与分组名', () => {
    expect(mentionItemMatches(library, '项目库')).toBe(true);
    expect(mentionItemMatches(library, '素材')).toBe(true);
    expect(mentionItemMatches(scene, '雨夜')).toBe(true);
    expect(mentionItemMatches(scene, '分镜')).toBe(true);
  });

  it('都不匹配时为 false', () => {
    expect(mentionItemMatches(library, '完全无关')).toBe(false);
  });
});

describe('ChatMentionMenu', () => {
  const renderMenu = (items = buildItems(), query = '') => {
    const anchorRef = createRef<HTMLDivElement>();
    const onClose = vi.fn();
    const utils = render(
      <div>
        <div ref={anchorRef} style={{ width: 320, height: 40 }} />
        <ChatMentionMenu items={items} query={query} onClose={onClose} anchorRef={anchorRef} />
      </div>,
    );
    return { ...utils, onClose, items };
  };

  it('按分组渲染并显示每组数量', () => {
    renderMenu();
    const listbox = screen.getByRole('listbox', { name: '引用资料' });
    expect(listbox.textContent).toContain('资料（1）');
    expect(listbox.textContent).toContain('分镜（1）');
    expect(listbox.textContent).toContain('对话（1）');
    expect(screen.getByText('项目库文件')).toBeTruthy();
    expect(screen.getByText('分镜 3')).toBeTruthy();
  });

  it('查询词过滤掉不匹配的项', () => {
    renderMenu(buildItems(), '雨夜');
    expect(screen.getByText('分镜 3')).toBeTruthy();
    expect(screen.queryByText('项目库文件')).toBeNull();
  });

  it('置灰项仍可见、点击不执行', () => {
    const { items } = renderMenu();
    const option = screen.getByText('对话《分镜重做》').closest('button') as HTMLButtonElement;
    expect(option.disabled).toBe(true);
    fireEvent.click(option);
    expect(items[2].run).not.toHaveBeenCalled();
    expect(screen.getByText('引用对话将在下一步（会话摘要）开放')).toBeTruthy();
  });

  it('点击可选项执行对应动作', () => {
    const { items } = renderMenu();
    fireEvent.click(screen.getByText('分镜 3').closest('button')!);
    expect(items[1].run).toHaveBeenCalledTimes(1);
    expect(items[0].run).not.toHaveBeenCalled();
  });

  it('键盘 Enter 执行当前高亮项，Esc 关闭', () => {
    const { items, onClose } = renderMenu();
    fireEvent.keyDown(window, { key: 'ArrowDown' });
    fireEvent.keyDown(window, { key: 'Enter' });
    expect(items[1].run).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('没有任何匹配项时不渲染浮层', () => {
    renderMenu(buildItems(), '完全无关');
    expect(screen.queryByRole('listbox', { name: '引用资料' })).toBeNull();
  });
});
