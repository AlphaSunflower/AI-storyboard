import { describe, expect, it } from 'vitest';
import {
  MOON_COMPOSER_SHORTCUT_IDS,
  MOON_PAGE_SHORTCUT_IDS,
  MOON_SHORTCUTS,
  groupMoonShortcuts,
  matchMoonShortcut,
  type ShortcutKeyEvent,
} from './moonShortcuts';

function key(overrides: Partial<ShortcutKeyEvent> = {}): ShortcutKeyEvent {
  return {
    key: '',
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    shiftKey: false,
    ...overrides,
  };
}

describe('matchMoonShortcut', () => {
  it('Ctrl+F 命中搜索，Cmd+F 同样命中', () => {
    expect(matchMoonShortcut(key({ key: 'f', code: 'KeyF', ctrlKey: true }))).toBe('search');
    expect(matchMoonShortcut(key({ key: 'f', code: 'KeyF', metaKey: true }))).toBe('search');
  });

  it('搜索不占用 Shift+F，避免和浏览器查找上一个冲突', () => {
    expect(matchMoonShortcut(key({ key: 'F', code: 'KeyF', ctrlKey: true, shiftKey: true }))).toBeNull();
  });

  it('Ctrl/Cmd + Shift + 1..9 按用户列出的顺序映射九个动作', () => {
    const expected = [
      'record',
      'status',
      'compact',
      'clear',
      'side-chat',
      'generation-center',
      'editor',
      'canvas',
      'library',
    ];
    expected.forEach((id, index) => {
      const digit = index + 1;
      expect(matchMoonShortcut(key({
        key: String(digit),
        code: `Digit${digit}`,
        ctrlKey: true,
        shiftKey: true,
      }))).toBe(id);
    });
  });

  it('Ctrl+/ 打开帮助，并兼容 ?（Shift+/）与键盘布局差异', () => {
    expect(matchMoonShortcut(key({ key: '/', code: 'Slash', ctrlKey: true }))).toBe('help');
    expect(matchMoonShortcut(key({ key: '?', ctrlKey: true, shiftKey: true }))).toBe('help');
  });

  it('没有 Ctrl/Cmd、带 Alt 或长按重复都不触发', () => {
    expect(matchMoonShortcut(key({ key: 'f', code: 'KeyF' }))).toBeNull();
    expect(matchMoonShortcut(key({ key: '1', code: 'Digit1', ctrlKey: true, shiftKey: true, altKey: true }))).toBeNull();
    expect(matchMoonShortcut(key({ key: 'f', code: 'KeyF', ctrlKey: true, repeat: true }))).toBeNull();
  });

  it('帮助面板覆盖全部快捷键，且每个 id 只出现在一个消费者分组里', () => {
    const ids = MOON_SHORTCUTS.map((shortcut) => shortcut.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const shortcut of MOON_SHORTCUTS) {
      const inPage = MOON_PAGE_SHORTCUT_IDS.has(shortcut.id);
      const inComposer = MOON_COMPOSER_SHORTCUT_IDS.has(shortcut.id);
      expect(inPage || inComposer).toBe(true);
      expect(inPage && inComposer).toBe(false);
    }
    expect(groupMoonShortcuts().map((group) => group.group)).toEqual(['对话与导航', '输入与上下文']);
  });
});
