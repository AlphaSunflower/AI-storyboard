import { useMemo, type CSSProperties } from 'react';
import { splitMentionSegments, type MentionRange } from '../../lib/mentionReferences';
import { agentChatTokens as T } from './agentChatTokens';

/**
 * 输入框的**文字度量契约**：正文 textarea 与镜像高亮层必须逐字对齐，所以两边共用同一份样式。
 *
 * <p>任何一边单独改 font-size / line-height / padding / 换行规则，都会让中文换行位置错开、
 * 蓝色标记飘到别的字上——改这里等于同时改两处，不要在两处各写一份。
 */
export const COMPOSER_TEXT_METRICS: CSSProperties = {
  margin: 0,
  padding: 0,
  border: 'none',
  fontFamily: 'inherit',
  fontSize: 16,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  overflowWrap: 'break-word',
  boxSizing: 'border-box',
  letterSpacing: 'inherit',
  textAlign: 'left',
};

/**
 * `@` 标记高亮的镜像层（方案 §2.3 已定方案 a）。
 *
 * <p>textarea 无法局部着色：把正文颜色设成 transparent、光标保持正色，后面叠一层渲染同一段
 * 文本的 div，只把引用标记染成品牌色。层本身 `pointer-events: none`，所有交互仍归 textarea。
 *
 * @param scrollTop textarea 的内部滚动量；内容超过高度上限时两层必须同步滚动
 * @param scrollbarWidth textarea 出现的滚动条宽度；镜像层要留出同样的右侧空间，避免文字宽度不一致
 */
export function ComposerMentionHighlight({
  value, ranges, scrollTop, scrollbarWidth,
}: {
  value: string;
  ranges: MentionRange[];
  scrollTop: number;
  scrollbarWidth: number;
}) {
  const segments = useMemo(() => splitMentionSegments(value, ranges), [value, ranges]);
  return (
    <div
      aria-hidden="true"
      className="chat-composer-highlight"
      style={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        pointerEvents: 'none',
        ...COMPOSER_TEXT_METRICS,
        color: T.ink,
        userSelect: 'none',
      }}
    >
      <div style={{ transform: `translateY(${-Math.max(0, scrollTop)}px)`, paddingRight: scrollbarWidth }}>
        {segments.map((segment, index) => (segment.mention ? (
          <span
            key={`mention-${index}`}
            data-mention="true"
            style={{ color: T.brand, fontWeight: 500 }}
          >
            {segment.text}
          </span>
        ) : (
          <span key={`text-${index}`}>{segment.text}</span>
        )))}
        {/* 末尾补一个换行占位：内容以换行结尾时，textarea 会多出一行高度，镜像层也要跟着长高 */}
        {'\u200b'}
      </div>
    </div>
  );
}
