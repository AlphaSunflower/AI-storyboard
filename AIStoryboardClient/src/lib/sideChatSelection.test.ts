import { describe, expect, it } from 'vitest';
import {
  buildSideChatSelectionContext,
  parseSideChatSelectionContext,
} from './sideChatSelection';

describe('sideChatSelection', () => {
  it('builds and parses selected text context without exposing the wrapper', () => {
    const content = buildSideChatSelectionContext([
      { id: 's1', text: '第一段原文', createdAt: 1 },
      { id: 's2', text: '第二段原文', createdAt: 2 },
    ], '这两段分别是什么意思？');

    expect(content).toContain('<selected-text-context>');
    expect(content).toContain('第一段原文');
    expect(parseSideChatSelectionContext(content)).toEqual({
      userText: '这两段分别是什么意思？',
      selections: ['第一段原文', '第二段原文'],
    });
  });
});
