import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatMentionMenu, MENTION_MENU_MAX_HEIGHT, mentionItemMatches, type MentionMenuItem } from './ChatMentionMenu';

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

  it('菜单高度有上限：可用空间再大也不铺满整屏，超出内部滚动', () => {
    // 输入卡在页面底部（top=800），可用高度 784px：不夹上限时菜单会顶到视口顶部
    const anchorRef = createRef<HTMLDivElement>();
    const rect = {
      top: 800, bottom: 900, left: 300, right: 620, width: 320, height: 100,
      x: 300, y: 800, toJSON: () => ({}),
    } as DOMRect;
    render(
      <div>
        <div ref={(node) => {
          if (!node) return;
          node.getBoundingClientRect = () => rect;
          (anchorRef as { current: HTMLDivElement | null }).current = node;
        }} />
        <ChatMentionMenu items={buildItems()} query="" onClose={vi.fn()} anchorRef={anchorRef} />
      </div>,
    );
    const menu = screen.getByRole('listbox', { name: '引用资料' });
    expect(menu.style.maxHeight).toBe(`${MENTION_MENU_MAX_HEIGHT}px`);
    expect(menu.style.overflowY).toBe('auto');
  });

  it('可用高度不足上限时按实际空间夹取，不留白', () => {
    const anchorRef = createRef<HTMLDivElement>();
    const rect = {
      top: 300, bottom: 400, left: 300, right: 620, width: 320, height: 100,
      x: 300, y: 300, toJSON: () => ({}),
    } as DOMRect;
    render(
      <div>
        <div ref={(node) => {
          if (!node) return;
          node.getBoundingClientRect = () => rect;
          (anchorRef as { current: HTMLDivElement | null }).current = node;
        }} />
        <ChatMentionMenu items={buildItems()} query="" onClose={vi.fn()} anchorRef={anchorRef} />
      </div>,
    );
    // 上方只剩 300 - 8(间距) - 8(边距) = 284px
    expect(screen.getByRole('listbox', { name: '引用资料' }).style.maxHeight).toBe('284px');
  });

  it('说明行不是候选项：不计入分组数量、不可选中', () => {
    const items: MentionMenuItem[] = [
      ...buildItems(),
      {
        id: 'conversation-more', group: 'conversation', icon: null, note: true,
        title: '还有 3 个会话未列出，输入标题可筛选', run: vi.fn(),
      },
    ];
    renderMenu(items);
    const listbox = screen.getByRole('listbox', { name: '引用资料' });
    expect(listbox.textContent).toContain('对话（1）');
    expect(screen.getByText('还有 3 个会话未列出，输入标题可筛选')).toBeTruthy();
    expect(screen.queryByRole('option', { name: /还有 3 个会话/ })).toBeNull();
    // 键盘选中项只落在真候选上
    fireEvent.keyDown(window, { key: 'ArrowDown' });
    const selected = screen.getAllByRole('option').filter((option) => option.getAttribute('aria-selected') === 'true');
    expect(selected).toHaveLength(1);
    expect(selected[0].textContent).toContain('分镜 3');
  });
});
