import { describe, expect, it } from 'vitest';
import {
  CANVAS_ACTION_SHORTCUTS,
  CANVAS_NODE_SHORTCUTS,
  canvasNodeShortcutFor,
  groupCanvasShortcuts,
  matchCanvasShortcut,
  type CanvasShortcutKeyEvent,
} from './canvasShortcuts';

function key(overrides: Partial<CanvasShortcutKeyEvent> = {}): CanvasShortcutKeyEvent {
  return {
    key: '',
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    shiftKey: false,
    ...overrides,
  };
}

describe('canvasShortcuts', () => {
  it('节点键位跟随左栏顺序，1–8 覆盖当前节点库', () => {
    expect(CANVAS_NODE_SHORTCUTS.map((shortcut) => shortcut.kind)).toEqual([
      'text',
      'image',
      'video',
      'storyboardPlanner',
      'shot',
      'imageGeneration',
      'videoGeneration',
      'videoMerge',
    ]);
    expect(CANVAS_NODE_SHORTCUTS.map((shortcut) => shortcut.key)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8']);
    expect(canvasNodeShortcutFor('imageGeneration')?.key).toBe('6');
  });

  it('数字键添加节点，单字母执行画布动作', () => {
    expect(matchCanvasShortcut(key({ key: '4' }))).toEqual({
      type: 'node',
      kind: 'storyboardPlanner',
      key: '4',
      label: '分镜拆解',
    });
    expect(matchCanvasShortcut(key({ key: 'u' }))).toMatchObject({ type: 'action', id: 'upload' });
    expect(matchCanvasShortcut(key({ key: 'L' }))).toMatchObject({ type: 'action', id: 'library' });
    expect(matchCanvasShortcut(key({ key: 'm' }))).toMatchObject({ type: 'action', id: 'map' });
    expect(matchCanvasShortcut(key({ key: 'b' }))).toMatchObject({ type: 'action', id: 'sidebar' });
    expect(matchCanvasShortcut(key({ key: 'o' }))).toMatchObject({ type: 'action', id: 'layout' });
    expect(matchCanvasShortcut(key({ key: 'e' }))).toMatchObject({ type: 'action', id: 'edges' });
    expect(matchCanvasShortcut(key({ key: 'h' }))).toMatchObject({ type: 'action', id: 'nodes' });
    expect(matchCanvasShortcut(key({ key: 'g' }))).toMatchObject({ type: 'action', id: 'records' });
    expect(matchCanvasShortcut(key({ key: 'q' }))).toMatchObject({ type: 'action', id: 'queue' });
  });

  it('? 打开帮助，但 Ctrl/Cmd/Alt 组合、Shift 字母和长按都不抢', () => {
    expect(matchCanvasShortcut(key({ key: '?' }))).toMatchObject({ type: 'action', id: 'help' });
    expect(matchCanvasShortcut(key({ key: 'u', ctrlKey: true }))).toBeNull();
    expect(matchCanvasShortcut(key({ key: 'u', metaKey: true }))).toBeNull();
    expect(matchCanvasShortcut(key({ key: 'u', altKey: true }))).toBeNull();
    expect(matchCanvasShortcut(key({ key: 'U', shiftKey: true }))).toBeNull();
    expect(matchCanvasShortcut(key({ key: 'u', repeat: true }))).toBeNull();
  });

  it('帮助面板列出所有节点和动作，键位不重复', () => {
    const groups = groupCanvasShortcuts();
    expect(groups.map((group) => group.group)).toEqual(['添加节点', '画布与视图', '分组']);
    const actionKeys = CANVAS_ACTION_SHORTCUTS.map((shortcut) => shortcut.key.toLowerCase());
    expect(new Set(actionKeys).size).toBe(actionKeys.length);
    expect(groups[0].items).toHaveLength(CANVAS_NODE_SHORTCUTS.length);
    expect(groups[1].items).toHaveLength(CANVAS_ACTION_SHORTCUTS.length);
  });
});
