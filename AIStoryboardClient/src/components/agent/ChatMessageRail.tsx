import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import { CHAT_MESSAGE_RAIL_WIDTH } from './chatDockLayout';
import { resolveRailStack, type ChatRailItem } from '../../lib/chatMessageRail';
import './ChatMessageRail.css';

/**
 * 消息导航行边栏：改造自 React Bits <LineSidebar/>（reactbits.dev，TypeScript + CSS 版），
 * 保留其单 rAF 邻近度引擎，按本项目形态裁剪为"只有刻度线 + 悬浮双行卡片"，
 * 并把标签换成可聚焦按钮，让键盘也能跳转。
 */

const PROXIMITY_RADIUS = 80;
const SMOOTHING_MS = 100;
/** 与消息列顶部/底部对齐的固定预留（标题栏 / 输入卡高度） */
const TOP_RESERVE = 56;
const BOTTOM_RESERVE = 148;
/** 悬浮卡片估算高度，用于上下夹紧 */
const CARD_HEIGHT = 58;
const CARD_EDGE = 8;

const easeSmooth = (progress: number) => progress * progress * (3 - 2 * progress);

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

export interface ChatMessageRailProps {
  items: ChatRailItem[];
  activeId: string | null;
  onSelect: (item: ChatRailItem) => void;
}

export function ChatMessageRail({ items, activeId, onSelect }: ChatMessageRailProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const itemRefs = useRef<(HTMLLIElement | null)[]>([]);
  const targetsRef = useRef<number[]>([]);
  const currentRef = useRef<number[]>([]);
  const rafRef = useRef<number | null>(null);
  const lastFrameRef = useRef(0);
  const activeIndexRef = useRef(-1);
  const reduceMotionRef = useRef(false);
  const [availableHeight, setAvailableHeight] = useState(0);
  const [hover, setHover] = useState<{ index: number; top: number } | null>(null);

  const stack = resolveRailStack(availableHeight, items.length);
  const visibleItems = items.slice(stack.start, stack.start + stack.count);
  const activeIndex = activeId ? visibleItems.findIndex((item) => item.id === activeId) : -1;
  activeIndexRef.current = activeIndex;

  useLayoutEffect(() => {
    itemRefs.current.length = visibleItems.length;
  }, [visibleItems.length]);

  // 单 rAF 循环：把每项的 --effect 指数平滑到目标值，长度/颜色/亮度因此同步变化
  const runFrame = useCallback((now: number) => {
    const dt = Math.min((now - lastFrameRef.current) / 1000, 0.05);
    lastFrameRef.current = now;
    const smoothing = Math.max(SMOOTHING_MS, 1) / 1000;
    const k = reduceMotionRef.current ? 1 : 1 - Math.exp(-dt / smoothing);

    let moving = false;
    const nodes = itemRefs.current;
    for (let index = 0; index < nodes.length; index++) {
      const node = nodes[index];
      if (!node) continue;
      const target = Math.max(targetsRef.current[index] ?? 0, activeIndexRef.current === index ? 1 : 0);
      const current = currentRef.current[index] ?? 0;
      const next = current + (target - current) * k;
      const settled = Math.abs(target - next) < 0.0015;
      const value = settled ? target : next;
      currentRef.current[index] = value;
      node.style.setProperty('--effect', value.toFixed(4));
      if (!settled) moving = true;
    }

    rafRef.current = moving ? requestAnimationFrame(runFrame) : null;
  }, []);

  const startLoop = useCallback(() => {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    lastFrameRef.current = performance.now();
    rafRef.current = requestAnimationFrame(runFrame);
  }, [runFrame]);

  const resetHoverTargets = useCallback(() => {
    targetsRef.current = targetsRef.current.map(() => 0);
    startLoop();
  }, [startLoop]);

  const handlePointerMove = useCallback((event: ReactPointerEvent<HTMLUListElement>) => {
    const list = listRef.current;
    if (!list) return;
    const pointerY = event.clientY - list.getBoundingClientRect().top;
    const nodes = itemRefs.current;
    const targets: number[] = [];
    for (let index = 0; index < nodes.length; index++) {
      const node = nodes[index];
      if (!node) {
        targets[index] = 0;
        continue;
      }
      const center = node.offsetTop + node.offsetHeight / 2;
      const distance = Math.abs(pointerY - center);
      targets[index] = easeSmooth(Math.max(0, 1 - distance / PROXIMITY_RADIUS));
    }
    targetsRef.current = targets;
    startLoop();
  }, [startLoop]);

  const showPreview = useCallback((index: number) => {
    const node = itemRefs.current[index];
    const root = rootRef.current;
    if (!node || !root) {
      setHover(null);
      return;
    }
    const center = node.offsetTop + (listRef.current?.offsetTop ?? 0) + node.offsetHeight / 2;
    const top = clampNumber(
      center - CARD_HEIGHT / 2,
      CARD_EDGE,
      root.clientHeight - CARD_HEIGHT - CARD_EDGE,
    );
    setHover({ index, top });
  }, []);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const measure = () => {
      const height = root.clientHeight - TOP_RESERVE - BOTTOM_RESERVE;
      setAvailableHeight(height > 0 ? height : 0);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    reduceMotionRef.current = query.matches;
    const onChange = () => {
      reduceMotionRef.current = query.matches;
      startLoop();
    };
    query.addEventListener?.('change', onChange);
    return () => query.removeEventListener?.('change', onChange);
  }, [startLoop]);

  // 激活项或刻度数量变化（切换会话、新增轮次）都重新起一轮动画
  useEffect(() => {
    startLoop();
  }, [activeIndex, stack.count, stack.gap, startLoop]);

  useEffect(() => () => {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
  }, []);

  // 鼠标离开整列（含从刻度移到卡片）时收敛邻近度并收起卡片
  const handlePointerLeave = useCallback(() => {
    resetHoverTargets();
    setHover(null);
  }, [resetHoverTargets]);

  const previewItem = hover ? visibleItems[hover.index] : undefined;

  return (
    <div
      ref={rootRef}
      className="chat-message-rail"
      style={{ '--rail-width': `${CHAT_MESSAGE_RAIL_WIDTH}px` } as CSSProperties}
      onPointerLeave={handlePointerLeave}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setHover(null);
      }}
    >
      <nav className="chat-message-rail__nav" aria-label="消息导航">
        <ul
          ref={listRef}
          className="chat-message-rail__list"
          onPointerMove={handlePointerMove}
          style={{ '--item-gap': `${stack.gap}px` } as CSSProperties}
        >
          {visibleItems.map((item, index) => (
            <li
              key={item.id}
              ref={(node) => {
                itemRefs.current[index] = node;
              }}
              className="chat-message-rail__item"
              style={{ height: stack.gap }}
            >
              <button
                type="button"
                className="chat-message-rail__button"
                aria-label={`跳转到：${item.title}`}
                aria-current={activeId === item.id ? 'true' : undefined}
                onClick={() => onSelect(item)}
                onPointerEnter={() => showPreview(index)}
                onFocus={() => showPreview(index)}
                onBlur={() => setHover(null)}
              >
                <span className="chat-message-rail__marker" aria-hidden="true" />
                <span className="chat-message-rail__label">{item.title}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
      {previewItem && hover && (
        <div
          className="chat-message-rail__card"
          role="presentation"
          style={{ top: hover.top }}
        >
          <span className="chat-message-rail__card-title">{previewItem.title}</span>
          {previewItem.preview && (
            <span className="chat-message-rail__card-preview">{previewItem.preview}</span>
          )}
        </div>
      )}
    </div>
  );
}

export default ChatMessageRail;
