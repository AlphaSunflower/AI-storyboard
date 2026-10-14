import { useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import gsap from 'gsap';
import { useRovingTabs } from '../../hooks/useRovingTabs';
import './RubberSegment.css';

export interface RubberSegmentItem<T extends string> {
  key: T;
  label: string;
  icon?: React.ReactNode;
  /** 选中项的开场/收尾拉伸幅度更大时用，普通角标传普通数字即可。 */
  count?: number;
  /** 选中后打开弹窗而不是切换内联视图，用于补 aria-haspopup。 */
  opensDialog?: boolean;
}

interface RubberSegmentProps<T extends string> {
  items: RubberSegmentItem<T>[];
  value: T;
  onChange: (key: T) => void;
  ariaLabel: string;
}

/** jsdom 等宿主可能没有 matchMedia，缺失时按「不降级」处理。 */
function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * 分段控件：珊瑚胶囊滑块在三段之间移动，途中横向拉伸再回弹。
 * 动画只用 transform（x / scaleX），宽度是布局属性，换段时直接设值不做补间。
 */
export function RubberSegment<T extends string>({
  items,
  value,
  onChange,
  ariaLabel,
}: RubberSegmentProps<T>) {
  const pillRef = useRef<HTMLSpanElement>(null);
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const mountedRef = useRef(false);
  const activeIndex = Math.max(0, items.findIndex((item) => item.key === value));
  const keys = useMemo(() => items.map((item) => item.key), [items]);
  const handleKeyDown = useRovingTabs(keys, activeIndex, onChange, itemRefs);

  const movePillTo = useCallback((index: number, animate: boolean) => {
    const pill = pillRef.current;
    const first = itemRefs.current[0];
    const target = itemRefs.current[index];
    if (!pill || !first || !target) return;

    gsap.killTweensOf(pill);
    // 三段等宽（1fr 栅格），所以宽度直接设值；left 与各段用同一 offsetLeft 参照系，
    // 这样滑块基准位置和段位天然对齐，不会把容器的 padding 算两遍。
    gsap.set(pill, { left: first.offsetLeft, width: target.offsetWidth });

    const x = target.offsetLeft - first.offsetLeft;
    if (!animate) {
      gsap.set(pill, { x, scaleX: 1 });
      return;
    }

    gsap.timeline()
      .to(pill, { x, duration: 0.42, ease: 'power3.out' }, 0)
      .to(pill, { scaleX: 1.16, duration: 0.14, ease: 'power2.out' }, 0)
      .to(pill, { scaleX: 1, duration: 0.34, ease: 'elastic.out(1, 0.5)' }, 0.14);
  }, []);

  useLayoutEffect(() => {
    movePillTo(activeIndex, mountedRef.current && !prefersReducedMotion());
    mountedRef.current = true;
  }, [activeIndex, movePillTo]);

  // 侧边栏宽度是响应式的，容器尺寸变化时把滑块瞬时对齐，避免留下拖影。
  useLayoutEffect(() => {
    const track = pillRef.current?.parentElement;
    if (!track || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => movePillTo(activeIndex, false));
    observer.observe(track);
    return () => observer.disconnect();
  }, [activeIndex, movePillTo]);

  return (
    <div className="rubber-segment" role="tablist" aria-label={ariaLabel}>
      <span ref={pillRef} className="rubber-segment__pill" aria-hidden="true" />
      {items.map((item, index) => {
        const selected = index === activeIndex;
        return (
          <button
            key={item.key}
            ref={(node) => {
              itemRefs.current[index] = node;
            }}
            type="button"
            role="tab"
            aria-selected={selected}
            // aria-haspopup 是全局属性，挂在 tab 上不违反规范；「生成记录」激活后确实弹对话框，
            // 提前告诉读屏用户比事后再播报更有用。
            aria-haspopup={item.opensDialog ? 'dialog' : undefined}
            tabIndex={selected ? 0 : -1}
            className={`rubber-segment__item${selected ? ' is-active' : ''}`}
            onClick={() => onChange(item.key)}
            onKeyDown={handleKeyDown}
          >
            {item.icon}
            <span>{item.label}</span>
            {item.count !== undefined && item.count > 0 && <b>{item.count}</b>}
          </button>
        );
      })}
    </div>
  );
}
