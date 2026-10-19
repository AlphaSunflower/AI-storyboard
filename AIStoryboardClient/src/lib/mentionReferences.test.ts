import { describe, expect, it } from 'vitest';
import type { AgentReference } from '../api/agent';
import {
  MENTION_LIMITS,
  activeReferences,
  conversationReference,
  countReferences,
  insertMention,
  mentionRanges,
  mentionSegmentAt,
  parseConversationDragPayload,
  referenceQuotaExceeded,
  removeMentionSegment,
  resolveMentionQuery,
  sceneReference,
  splitMentionSegments,
  uniqueMentionToken,
} from './mentionReferences';

function sceneRef(id: string, sceneNumber: number, token?: string): AgentReference {
  return {
    kind: 'scene',
    id,
    token: token ?? `@分镜${sceneNumber}`,
    name: `分镜${sceneNumber}`,
    sceneNumber,
  };
}

describe('resolveMentionQuery', () => {
  it('把光标前最近的 @ 之后到光标的内容当成查询词', () => {
    expect(resolveMentionQuery('帮我看看 @分镜', 8)).toEqual({ start: 5, query: '分镜' });
  });

  it('整串中间插入 @ 也按光标判定（不能退化成 trim 开头判定）', () => {
    expect(resolveMentionQuery('前半段 @镜', 6)).toEqual({ start: 4, query: '镜' });
  });

  it('查询词里出现空白就不再是提及', () => {
    expect(resolveMentionQuery('@分镜 3', 5)).toBeNull();
    expect(resolveMentionQuery('@分镜\n', 4)).toBeNull();
  });

  it('邮箱等 @ 前带字母数字的场景不触发', () => {
    expect(resolveMentionQuery('a@b', 3)).toBeNull();
  });

  it('光标不在末尾时只看光标之前', () => {
    // '把 @分镜3 再拉近'，光标停在 @ 之后两位
    expect(resolveMentionQuery('把 @分镜3 再拉近', 4)).toEqual({ start: 2, query: '分' });
  });

  it('没有 @ 时返回 null', () => {
    expect(resolveMentionQuery('普通文本', 4)).toBeNull();
  });
});

describe('insertMention', () => {
  it('用标记加空格替换掉刚输入的 @查询词，并把光标移到标记之后', () => {
    const result = insertMention('请把 @分镜 拉近', 7, 3, '@分镜3');
    expect(result.value).toBe('请把 @分镜3 拉近');
    expect(result.caret).toBe('请把 @分镜3 '.length);
  });

  it('连续插入多个标记时逐个累加', () => {
    let value = '@';
    let caret = 1;
    for (const token of ['@分镜1', '@分镜2']) {
      const inserted = insertMention(value, caret, resolveMentionQuery(value, caret)?.start ?? caret, token);
      value = inserted.value;
      caret = inserted.caret;
    }
    expect(value).toBe('@分镜1 @分镜2 ');
  });
});

describe('高亮区间与原子删除', () => {
  const references = [sceneRef('scene-3', 3), sceneRef('scene-1', 1)];

  it('只高亮正文里真实存在的标记', () => {
    const ranges = mentionRanges('看 @分镜3 和 @不存在', references);
    expect(ranges).toHaveLength(1);
    expect(ranges[0].start).toBe(2);
    expect(ranges[0].end).toBe(6);
  });

  it('切片段时普通文本逐字保留', () => {
    const value = '看 @分镜3 的效果';
    const segments = splitMentionSegments(value, mentionRanges(value, references));
    expect(segments.map((segment) => segment.text).join('')).toBe(value);
    expect(segments.filter((segment) => segment.mention).map((segment) => segment.text)).toEqual(['@分镜3']);
  });

  it('光标在标记内或紧跟其后时命中整个标记', () => {
    const value = '@分镜3 拉近';
    expect(mentionSegmentAt(value, 3, references)?.reference.id).toBe('scene-3');
    expect(mentionSegmentAt(value, 4, references)?.reference.id).toBe('scene-3');
    // 标记后的空格之后也算命中，方便一次退格把 "@分镜3 " 整段吃掉
    expect(mentionSegmentAt(value, 5, references)?.reference.id).toBe('scene-3');
  });

  it('退格删除整个标记（含其后空格），返回新的光标位置', () => {
    const removed = removeMentionSegment('看 @分镜3 的效果', 6, references);
    expect(removed?.value).toBe('看 的效果');
    expect(removed?.caret).toBe(2);
    expect(removed?.reference.id).toBe('scene-3');
  });

  it('光标在标记之前时不动它（走浏览器默认退格）', () => {
    expect(removeMentionSegment('看 @分镜3 的效果', 2, references)).toBeNull();
    expect(removeMentionSegment('看 @分镜3 的效果', 0, references)).toBeNull();
  });
});

describe('引用目录与额度', () => {
  it('发送时只带正文里还存在的引用（删掉标记=撤销引用）', () => {
    const references = [sceneRef('scene-3', 3), sceneRef('scene-1', 1)];
    expect(activeReferences('只看 @分镜3', references).map((item) => item.id)).toEqual(['scene-3']);
    expect(activeReferences('什么都没了', references)).toEqual([]);
  });

  it('同名资料用 #2 后缀区分（否则定位与后端解析都会指错）', () => {
    const existing = [sceneRef('scene-1', 3)];
    expect(uniqueMentionToken('分镜3', existing)).toBe('@分镜3#2');
    expect(uniqueMentionToken('分镜4', existing)).toBe('@分镜4');
  });

  it('额度：总数 10 / 分镜 5 / 对话 3', () => {
    const scenes = Array.from({ length: MENTION_LIMITS.scene }, (_, index) => sceneRef(`s-${index}`, index + 1));
    expect(referenceQuotaExceeded(scenes, 'scene')).toBe(true);
    expect(referenceQuotaExceeded(scenes, 'conversation')).toBe(false);
    const conversations = Array.from({ length: MENTION_LIMITS.conversation }, (_, index) => ({
      kind: 'conversation' as const,
      id: `c-${index}`,
      token: `@对话${index}`,
      name: `对话${index}`,
    }));
    expect(referenceQuotaExceeded(conversations, 'conversation')).toBe(true);
    expect(countReferences([...scenes, ...conversations])).toMatchObject({
      total: MENTION_LIMITS.scene + MENTION_LIMITS.conversation,
      scene: MENTION_LIMITS.scene,
      conversation: MENTION_LIMITS.conversation,
    });
  });
});

describe('引用构造', () => {
  it('分镜引用带 sceneId 与分镜号', () => {
    const reference = sceneReference({ id: 'scene-9', sceneNumber: 9 }, []);
    expect(reference).toMatchObject({
      kind: 'scene', id: 'scene-9', token: '@分镜9', name: '分镜9', sceneNumber: 9,
    });
  });

  it('对话引用带摘要状态（P2 摘要就绪度展示用）', () => {
    const reference = conversationReference(
      { id: 'conversation-2', title: '分镜重做', messageCount: 23, summaryStatus: 'ready' },
      [],
    );
    expect(reference).toMatchObject({
      kind: 'conversation',
      id: 'conversation-2',
      token: '@对话《分镜重做》',
      conversationTitle: '分镜重做',
      messageCount: 23,
      summaryStatus: 'ready',
    });
  });

  it('标题里已含书名号时不重复包裹', () => {
    const reference = conversationReference({ id: 'c-1', title: '《已包裹》' }, []);
    expect(reference.token).toBe('@对话《已包裹》');
  });

  it('解析左侧会话拖拽负载，坏数据返回 null', () => {
    expect(parseConversationDragPayload(JSON.stringify({
      id: 'c-1', title: '分镜重做', messageCount: 3,
    }))).toEqual({ id: 'c-1', title: '分镜重做', messageCount: 3, summaryStatus: undefined });
    expect(parseConversationDragPayload('not-json')).toBeNull();
    expect(parseConversationDragPayload(JSON.stringify({ title: '缺 id' }))).toBeNull();
  });
});
