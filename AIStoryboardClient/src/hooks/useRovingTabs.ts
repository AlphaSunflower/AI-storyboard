import { useCallback } from 'react';

/**
 * WAI-ARIA tablist 的键盘行为：整组只占一个 Tab 位（选中项 tabIndex=0，其余 -1），
 * 方向键在选项间移动并跟随选中，Home / End 跳到首尾。
 *
 * 调用方自己持有按钮 ref 数组（RubberSegment / AssetFilterNav 还要用它们量宽度），
 * 这里只负责按键与焦点搬运。
 */
export function useRovingTabs<T extends string>(
  keys: T[],
  activeIndex: number,
  onSelect: (key: T) => void,
  itemRefs: React.RefObject<Array<HTMLButtonElement | null>>,
) {
  const handleKeyDown = useCallback((event: React.KeyboardEvent<HTMLButtonElement>) => {
    const last = keys.length - 1;
    if (last < 0) return;

    let next: number;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      next = activeIndex >= last ? 0 : activeIndex + 1;
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      next = activeIndex <= 0 ? last : activeIndex - 1;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = last;
    } else {
      return;
    }

    event.preventDefault();
    onSelect(keys[next]);
    itemRefs.current[next]?.focus();
  }, [activeIndex, itemRefs, keys, onSelect]);

  return handleKeyDown;
}
