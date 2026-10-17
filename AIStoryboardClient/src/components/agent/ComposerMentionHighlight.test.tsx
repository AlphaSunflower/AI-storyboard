import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AgentReference } from '../../api/agent';
import { mentionRanges } from '../../lib/mentionReferences';
import { agentChatTokens } from './agentChatTokens';
import { ComposerMentionHighlight } from './ComposerMentionHighlight';

const references: AgentReference[] = [
  { kind: 'scene', id: 'scene-3', token: '@分镜3', name: '分镜3', sceneNumber: 3 },
];

afterEach(cleanup);

describe('ComposerMentionHighlight（输入框镜像高亮层）', () => {
  it('只把引用标记染成品牌色，其余文本逐字保留', () => {
    const value = '把 @分镜3 的镜头拉近一点';
    const { container } = render(
      <ComposerMentionHighlight
        value={value}
        ranges={mentionRanges(value, references)}
        scrollTop={0}
        scrollbarWidth={0}
      />,
    );

    // 零宽占位符只是为了让空行也有高度，比对时剔除
    expect(container.textContent?.replace(/\u200b/g, '')).toBe(value);
    const marked = container.querySelectorAll('[data-mention="true"]');
    expect(marked).toHaveLength(1);
    expect(marked[0].textContent).toBe('@分镜3');
    expect((marked[0] as HTMLElement).style.color).toBe(agentChatTokens.brand);
  });

  it('没有标记时全是普通文本（不着色）', () => {
    const value = '普通的一句话';
    const { container } = render(
      <ComposerMentionHighlight value={value} ranges={[]} scrollTop={0} scrollbarWidth={0} />,
    );
    expect(container.querySelectorAll('[data-mention="true"]')).toHaveLength(0);
    expect(container.textContent?.replace(/\u200b/g, '')).toBe(value);
  });

  it('层本身不吃事件（交互全归 textarea），并跟随 textarea 的内部滚动', () => {
    const value = '开头\n第二行 @分镜3';
    const { container } = render(
      <ComposerMentionHighlight
        value={value}
        ranges={mentionRanges(value, references)}
        scrollTop={12}
        scrollbarWidth={7}
      />,
    );

    const layer = container.querySelector<HTMLElement>('.chat-composer-highlight');
    expect(layer?.style.pointerEvents).toBe('none');
    const scroller = layer?.firstElementChild as HTMLElement;
    expect(scroller.style.transform).toContain('-12px');
    expect(scroller.style.paddingRight).toBe('7px');
  });

  it('与 textarea 共用同一份字体度量（换行位置才不会错开）', () => {
    const { container } = render(
      <ComposerMentionHighlight value="@分镜3" ranges={mentionRanges('@分镜3', references)} scrollTop={0} scrollbarWidth={0} />,
    );
    const layer = container.querySelector<HTMLElement>('.chat-composer-highlight');
    expect(layer?.style.whiteSpace).toBe('pre-wrap');
    expect(layer?.style.fontSize).toBe('16px');
    expect(layer?.style.lineHeight).toBe('1.6');
    expect(layer?.style.padding).toBe('0px');
  });
});
