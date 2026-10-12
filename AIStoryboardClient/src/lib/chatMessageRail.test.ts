import { describe, expect, it } from 'vitest';
import type { AgentMessage } from '../api/agent';
import {
  RAIL_MAX_GAP,
  RAIL_MIN_GAP,
  buildChatRailItems,
  pickActiveRailId,
  railPreviewFromMarkdown,
  resolveRailStack,
  truncateRailTitle,
} from './chatMessageRail';

function message(
  id: string,
  role: 'user' | 'assistant',
  content: string,
  metadata?: AgentMessage['metadata'],
): AgentMessage {
  return {
    id,
    conversationId: 'conv-1',
    role,
    content,
    createdAt: '2026-10-12T08:00:00.000Z',
    metadata,
  };
}

describe('truncateRailTitle', () => {
  it('keeps titles up to 15 characters untouched', () => {
    expect(truncateRailTitle('123456789012345')).toBe('123456789012345');
    expect(truncateRailTitle('你好世界这是一条很长的中文消息')).toBe('你好世界这是一条很长的中文消息');
  });

  it('keeps the first 12 characters and adds an ellipsis beyond 15', () => {
    expect(truncateRailTitle('1234567890123456')).toBe('123456789012…');
    expect(truncateRailTitle('你好世界这是一条很长的中文消息啊')).toBe('你好世界这是一条很长的中…');
  });

  it('counts emoji by code point instead of UTF-16 units', () => {
    expect(truncateRailTitle('🎬分镜脚本设计思路说明文档内容啊')).toBe('🎬分镜脚本设计思路说明文…');
  });

  it('folds newlines and repeated spaces before measuring', () => {
    expect(truncateRailTitle('  你好\n\n   世界  ')).toBe('你好 世界');
  });

  it('returns an empty string for blank content', () => {
    expect(truncateRailTitle('   \n  ')).toBe('');
  });
});

describe('railPreviewFromMarkdown', () => {
  it('takes the last paragraph and drops markdown syntax', () => {
    const text = '# 分镜脚本\n\n第一段说明文字\n\n- 列表项一\n- 列表项二\n\n最后一段：确认镜头调度';

    expect(railPreviewFromMarkdown(text)).toBe('最后一段：确认镜头调度');
  });

  it('ignores fenced code blocks', () => {
    const text = '第一段内容\n\n```ts\nconst a = 1;\n```\n\n第二段内容';

    expect(railPreviewFromMarkdown(text)).toBe('第二段内容');
    expect(railPreviewFromMarkdown('```ts\nconst a = 1;\n```')).toBe('');
  });

  it('unwraps links and emphasis', () => {
    expect(railPreviewFromMarkdown('[方案文档](https://example.com) **已经** 就绪')).toBe('方案文档 已经 就绪');
  });

  it('truncates the preview at 40 code points', () => {
    const long = '甲'.repeat(45);
    const preview = railPreviewFromMarkdown(long);

    expect(preview).toBe(`${'甲'.repeat(40)}…`);
    expect(Array.from(preview).length).toBe(41);
  });

  it('returns an empty string for empty content', () => {
    expect(railPreviewFromMarkdown('')).toBe('');
    expect(railPreviewFromMarkdown('   \n\n  ')).toBe('');
  });
});

describe('buildChatRailItems', () => {
  it('pairs every user message with the last paragraph of its own reply', () => {
    const items = buildChatRailItems([
      message('u1', 'user', '帮我设计一个科幻分镜'),
      message('a1', 'assistant', '先确认设定。\n\n镜头一从太空舱内部推进'),
      message('u2', 'user', '换成雨天场景'),
      message('a2', 'assistant', '好的。\n\n雨夜霓虹下的长镜头'),
    ]);

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({
      id: 'u1',
      anchorId: 'u1',
      title: '帮我设计一个科幻分镜',
      preview: '镜头一从太空舱内部推进',
    });
    expect(items[1]).toMatchObject({ id: 'u2', title: '换成雨天场景', preview: '雨夜霓虹下的长镜头' });
  });

  it('skips empty assistant records and stops at the next user message', () => {
    const items = buildChatRailItems([
      message('u1', 'user', '第一轮'),
      message('a1', 'assistant', ''),
      message('a2', 'assistant', '真正的回复正文'),
      message('u2', 'user', '第二轮'),
    ]);

    expect(items.map((item) => item.preview)).toEqual(['真正的回复正文', '']);
  });

  it('leaves the preview empty while the round has no assistant text yet', () => {
    const items = buildChatRailItems([
      message('u1', 'user', '第一轮'),
      message('a1', 'assistant', '第一轮回复'),
      message('u2', 'user', '第二轮刚开始'),
    ]);

    expect(items).toHaveLength(2);
    expect(items[1].preview).toBe('');
  });

  it('ignores hidden pending-card placeholders and falls back for attachment-only sends', () => {
    const items = buildChatRailItems([
      message('u1', 'user', '第一轮'),
      message('a1', 'assistant', '第一轮回复'),
      message('u2', 'user', '   ', {
        messageInput: { attachments: [{ id: 'att-1', name: '镜头参考.png' } as never] },
      }),
      message('a2', 'assistant', '同一条提示文案', {
        kind: 'hitl',
        hitl: { formContent: '同一条提示文案', actions: [], pending: true },
      }),
      message('a3', 'assistant', '第二轮回复正文'),
    ]);

    expect(items).toHaveLength(2);
    expect(items[1]).toMatchObject({ title: '（附件消息）', preview: '第二轮回复正文' });
  });

  it('returns nothing when there is no user message', () => {
    expect(buildChatRailItems([message('a1', 'assistant', '只有助手消息')])).toEqual([]);
  });
});

describe('pickActiveRailId', () => {
  const anchors = [
    { id: 'u1', top: 100 },
    { id: 'u2', top: 400 },
    { id: 'u3', top: 900 },
  ];

  it('picks the last round above the 35% line', () => {
    // 容器 100..1100，阈值线 = 100 + 1000 * 0.35 = 450
    expect(pickActiveRailId(anchors, 100, 1000)).toBe('u2');
    expect(pickActiveRailId(anchors, 100, 2400)).toBe('u3');
    expect(pickActiveRailId(anchors, 100, 400)).toBe('u1');
  });

  it('falls back to the first round and tolerates an empty list', () => {
    const belowThreshold = [{ id: 'u1', top: 900 }];

    expect(pickActiveRailId(belowThreshold, 100, 1000)).toBe('u1');
    expect(pickActiveRailId([], 0, 800)).toBeNull();
  });
});

describe('resolveRailStack', () => {
  it('lays every round out at the maximum gap when there is room to spare', () => {
    expect(resolveRailStack(600, 10)).toEqual({ start: 0, count: 10, gap: RAIL_MAX_GAP });
  });

  it('shrinks the gap down to the minimum before dropping rounds', () => {
    expect(resolveRailStack(500, 50)).toEqual({ start: 0, count: 50, gap: RAIL_MIN_GAP });
  });

  it('keeps only the newest rounds that fit at the minimum gap', () => {
    expect(resolveRailStack(300, 50)).toEqual({ start: 20, count: 30, gap: RAIL_MIN_GAP });
  });

  it('lays everything out before the first measurement', () => {
    expect(resolveRailStack(0, 3)).toEqual({ start: 0, count: 3, gap: RAIL_MAX_GAP });
    expect(resolveRailStack(0, 0)).toEqual({ start: 0, count: 0, gap: RAIL_MAX_GAP });
  });
});
