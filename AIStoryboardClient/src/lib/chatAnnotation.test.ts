import { afterEach, describe, expect, it } from 'vitest';
import {
  buildAnnotationContext,
  findQuoteRange,
  selectionOccurrence,
  stripAnnotationContext,
} from './chatAnnotation';

function mount(html: string): HTMLDivElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('buildAnnotationContext / stripAnnotationContext', () => {
  it('passes plain user text through when there are no annotations', () => {
    expect(buildAnnotationContext([], '  你好  ')).toBe('你好');
  });

  it('writes quote and comment, falling back to 无 for empty comments', () => {
    const content = buildAnnotationContext([{ quote: '原文', comment: '' }], '问题');
    expect(content).toContain('【引用注释】');
    expect(content).toContain('1. 原文：原文');
    expect(content).toContain('评论：无');
    expect(content).toContain('问题');
  });

  it('round-trips back to the user text', () => {
    const content = buildAnnotationContext([{ quote: '第一段\n第二段', comment: '看看这里' }], '我的问题');
    expect(stripAnnotationContext(content)).toEqual({ userText: '我的问题', hasContext: true });
  });

  it('leaves content without a context block untouched', () => {
    expect(stripAnnotationContext('普通消息')).toEqual({ userText: '普通消息', hasContext: false });
  });
});

describe('findQuoteRange', () => {
  it('returns null for a blank quote', () => {
    const root = mount('<p>hello</p>');
    expect(findQuoteRange(root, '   ')).toBeNull();
  });

  it('finds the first occurrence', () => {
    const root = mount('<p>cat dog cat bird</p>');
    const range = findQuoteRange(root, 'cat');
    expect(range?.toString()).toBe('cat');
    expect(range?.startOffset).toBe(0);
  });

  it('finds the requested occurrence', () => {
    const root = mount('<p>cat dog cat bird</p>');
    const range = findQuoteRange(root, 'cat', 1);
    expect(range?.startOffset).toBe(8);
    expect(range?.endOffset).toBe(11);
  });

  it('matches across inline formatting', () => {
    const root = mount('<p>hello <strong>wide</strong> world</p>');
    const range = findQuoteRange(root, 'hello wide world');
    expect(range?.toString()).toBe('hello wide world');
  });

  it('matches across block boundaries using collapsed whitespace', () => {
    const root = mount('<p>line one</p><p>line two</p>');
    const range = findQuoteRange(root, 'line one\nline two');
    expect(range).not.toBeNull();
    expect(range?.startContainer.textContent).toBe('line one');
    expect(range?.endContainer.textContent).toBe('line two');
  });

  it('returns null when the quote is not present', () => {
    const root = mount('<p>only this</p>');
    expect(findQuoteRange(root, 'missing')).toBeNull();
  });
});

describe('selectionOccurrence', () => {
  it('counts earlier identical matches', () => {
    const root = mount('<p>cat dog cat bird</p>');
    const textNode = root.querySelector('p')!.firstChild as Text;
    const range = document.createRange();
    range.setStart(textNode, 8);
    range.setEnd(textNode, 11);
    expect(selectionOccurrence(root, range, 'cat')).toBe(1);
  });

  it('returns 0 for the first occurrence', () => {
    const root = mount('<p>cat dog cat bird</p>');
    const textNode = root.querySelector('p')!.firstChild as Text;
    const range = document.createRange();
    range.setStart(textNode, 0);
    range.setEnd(textNode, 3);
    expect(selectionOccurrence(root, range, 'cat')).toBe(0);
  });
});
