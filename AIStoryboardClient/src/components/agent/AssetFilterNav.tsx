import { useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import gsap from 'gsap';
import { FileText, Film, Images, LayoutGrid, LayoutList, Package } from 'lucide-react';
import { useRovingTabs } from '../../hooks/useRovingTabs';
import type { AssetsTab } from './assetBrowserModel';
import './AssetFilterNav.css';

/** goo 滤镜的 SVG 定义 id；同页只有一个资产筛选，固定 id 即可。 */
const GOO_FILTER_ID = 'asset-filter-goo';

const FILTER_ITEMS: { key: AssetsTab; label: string; icon: React.ReactNode }[] = [
  { key: 'all', label: '全部', icon: <LayoutGrid size={13} strokeWidth={1.9} /> },
  { key: 'image', label: '图片', icon: <Images size={13} strokeWidth={1.9} /> },
  { key: 'video', label: '视频', icon: <Film size={13} strokeWidth={1.9} /> },
  { key: 'document', label: '文档', icon: <FileText size={13} strokeWidth={1.9} /> },
  { key: 'storyboard', label: '分镜', icon: <LayoutList size={13} strokeWidth={1.9} /> },
  { key: 'other', label: '其他', icon: <Package size={13} strokeWidth={1.9} /> },
];

interface AssetFilterNavProps {
  value: AssetsTab;
  onChange: (tab: AssetsTab) => void;
}

/** jsdom 等宿主可能没有 matchMedia，缺失时按「不降级」处理。 */
function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * 会话资产筛选：六项带图标，珊瑚色 blob 在选项间移动并黏连成液滴。
 * blob 与选项格子放在同一个栅格层里，所以位置天然对齐，不需要额外的坐标换算；
 * 滤镜只作用于纯色块背景层，文字不参与，避免糊字。
 */
export function AssetFilterNav({ value, onChange }: AssetFilterNavProps) {
  const gooRef = useRef<HTMLDivElement>(null);
  const blobRef = useRef<HTMLSpanElement>(null);
  const chipRefs = useRef<Array<HTMLSpanElement | null>>([]);
  const mountedRef = useRef(false);
  const activeIndex = Math.max(0, FILTER_ITEMS.findIndex((item) => item.key === value));
  const itemKeys = useMemo(() => FILTER_ITEMS.map((item) => item.key), []);
  const itemButtonRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const handleKeyDown = useRovingTabs(itemKeys, activeIndex, onChange, itemButtonRefs);

  const moveBlobTo = useCallback((index: number, animate: boolean) => {
    const blob = blobRef.current;
    const target = chipRefs.current[index];
    if (!blob || !target) return;

    gsap.killTweensOf(blob);
    // blob 与 chip 同属 goo 层，offsetLeft 参照系一致，直接取用即可。
    gsap.set(blob, { width: target.offsetWidth });

    if (!animate) {
      gsap.set(blob, { x: target.offsetLeft });
      return;
    }

    gsap.timeline()
      .to(blob, { x: target.offsetLeft, duration: 0.44, ease: 'power3.out' }, 0)
      .to(blob, { scaleX: 1.18, duration: 0.15, ease: 'power2.out' }, 0)
      .to(blob, { scaleX: 1, duration: 0.36, ease: 'elastic.out(1, 0.5)' }, 0.15);
  }, []);

  useLayoutEffect(() => {
    moveBlobTo(activeIndex, mountedRef.current && !prefersReducedMotion());
    mountedRef.current = true;
  }, [activeIndex, moveBlobTo]);

  useLayoutEffect(() => {
    const layer = gooRef.current;
    if (!layer || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => moveBlobTo(activeIndex, false));
    observer.observe(layer);
    return () => observer.disconnect();
  }, [activeIndex, moveBlobTo]);

  return (
    <div className="asset-filter">
      <svg className="asset-filter__defs" width="0" height="0" aria-hidden="true" focusable="false">
        <defs>
          <filter id={GOO_FILTER_ID} x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur in="SourceGraphic" stdDeviation="4" result="blur" />
            <feColorMatrix
              in="blur"
              mode="matrix"
              values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 18 -8"
            />
          </filter>
        </defs>
      </svg>

      <div className="asset-filter__track">
        <div ref={gooRef} className="asset-filter__goo" aria-hidden="true">
          <span ref={blobRef} className="asset-filter__blob" />
          {FILTER_ITEMS.map((item, index) => (
            <span
              key={item.key}
              ref={(node) => {
                chipRefs.current[index] = node;
              }}
              className={`asset-filter__chip${index === activeIndex ? ' is-active' : ''}`}
            />
          ))}
        </div>

        <div className="asset-filter__items" role="tablist" aria-label="会话资产筛选">
          {FILTER_ITEMS.map((item, index) => {
            const selected = index === activeIndex;
            return (
              <button
                key={item.key}
                ref={(node) => {
                  itemButtonRefs.current[index] = node;
                }}
                type="button"
                role="tab"
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                className={`asset-filter__item${selected ? ' is-active' : ''}`}
                onClick={() => onChange(item.key)}
                onKeyDown={handleKeyDown}
              >
                {item.icon}
                <span>{item.label}</span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
