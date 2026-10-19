import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import './VirtualAssetGrid.css';

interface VirtualAssetGridProps<T extends { id: string }> {
  items: T[];
  scrollRef: RefObject<HTMLElement | null>;
  renderItem: (item: T) => ReactNode;
  minColumnWidth?: number;
  rowHeight?: number;
  gap?: number;
  overscanRows?: number;
}

/**
 * 资产网格窗口化：只挂载可视行 + 少量 overscan。
 * 外层 .library-folder-grid 仍是网格；本组件以 grid-column:1/-1 占满整行。
 */
export function VirtualAssetGrid<T extends { id: string }>({
  items,
  scrollRef,
  renderItem,
  minColumnWidth = 128,
  rowHeight = 138,
  gap = 14,
  overscanRows = 3,
}: VirtualAssetGridProps<T>) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(0);
  const [scrollTop, setScrollTop] = useState(0);
  const [gridOffset, setGridOffset] = useState(0);

  useEffect(() => {
    const root = rootRef.current;
    const scroller = scrollRef.current;
    if (!root || !scroller) return;

    const measure = () => {
      const nextWidth = root.clientWidth;
      if (nextWidth > 0) setWidth(nextWidth);
      if (scroller.clientHeight > 0) setViewportHeight(scroller.clientHeight);
      setGridOffset(
        root.getBoundingClientRect().top
        - scroller.getBoundingClientRect().top
        + scroller.scrollTop,
      );
    };
    const onScroll = () => setScrollTop(scroller.scrollTop);

    measure();
    onScroll();
    scroller.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', measure);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(root);
    observer?.observe(scroller);
    return () => {
      scroller.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, [scrollRef, items.length]);

  const availableWidth = width || 800;
  const columns = Math.max(1, Math.floor((availableWidth + 10) / (minColumnWidth + 10)));
  const stride = rowHeight + gap;
  const rowCount = Math.ceil(items.length / columns);
  const totalHeight = Math.max(0, rowCount * rowHeight + Math.max(0, rowCount - 1) * gap);
  const relativeScroll = Math.max(0, scrollTop - gridOffset);
  const effectiveViewport = viewportHeight || 760;
  const startRow = Math.max(0, Math.floor(relativeScroll / stride) - overscanRows);
  const endRow = Math.min(
    rowCount - 1,
    Math.ceil((relativeScroll + effectiveViewport) / stride) + overscanRows,
  );

  const rows: Array<{ index: number; start: number }> = [];
  for (let row = startRow; row <= endRow; row += 1) {
    rows.push({ index: row, start: row * columns });
  }

  return (
    <div
      ref={rootRef}
      className="library-virtual-grid"
      style={{ height: totalHeight }}
      data-virtualized="true"
    >
      {rows.map(({ index, start }) => (
        <div
          key={index}
          className="library-virtual-grid__row"
          style={{
            top: index * stride,
            height: rowHeight,
            gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
          }}
        >
          {items.slice(start, start + columns).map((item) => (
            <div key={item.id} className="library-virtual-grid__cell">
              {renderItem(item)}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
