import { useEffect, useState } from 'react';
import { AGENT_ASSET_DRAG_MIME } from '../lib/agentAssetDrag';

/** 侧边栏左边缘：视口右侧先让出 rightOffset（app 子窗口），再让出侧边栏自身宽度。 */
export function resolveSidebarLeftEdge(
  viewportWidth: number,
  sidebarWidth: number,
  rightOffset: number,
): number {
  return viewportWidth - rightOffset - sidebarWidth;
}

/** 「出了侧边栏」就是指针跑到它左边缘之外 —— 用坐标判定，不依赖事件落在哪个子树。 */
export function isPointerOutsideSidebar(clientX: number, sidebarLeft: number): boolean {
  return clientX < sidebarLeft;
}

function isAgentAssetDrag(event: DragEvent): boolean {
  const types = event.dataTransfer?.types;
  return types ? Array.from(types).includes(AGENT_ASSET_DRAG_MIME) : false;
}

/**
 * 从生成中心往聊天区拖素材时，判断指针是否已经离开侧边栏。
 *
 * 之所以不走「聊天列上的 dragover」：侧边栏是 fixed 的兄弟节点，拖到它上面时事件不会冒泡到聊天列，
 * 但 dragleave 会冒泡，指针从列背景移到输入框上就会误判成「离开了」，需要额外的深度计数才不闪。
 * 直接比坐标既准又简单。
 *
 * @param enabled 侧边栏确实处于停靠打开状态时才启用；窄屏是覆盖式抽屉，没有「绕过侧边栏」这回事
 */
export function useAgentAssetDragOutsideSidebar(enabled: boolean, sidebarLeft: number): boolean {
  const [dragging, setDragging] = useState(false);
  const [outside, setOutside] = useState(false);

  useEffect(() => {
    const onDragStart = (event: DragEvent) => {
      if (isAgentAssetDrag(event)) setDragging(true);
    };
    const onEnd = () => {
      setDragging(false);
      setOutside(false);
    };
    window.addEventListener('dragstart', onDragStart);
    window.addEventListener('dragend', onEnd);
    // 拖到窗口外松手时只有 drop 不一定有 dragend，两个都接才能保证状态复位。
    window.addEventListener('drop', onEnd);
    return () => {
      window.removeEventListener('dragstart', onDragStart);
      window.removeEventListener('dragend', onEnd);
      window.removeEventListener('drop', onEnd);
    };
  }, []);

  useEffect(() => {
    if (!enabled || !dragging) {
      setOutside(false);
      return undefined;
    }
    const onDragOver = (event: DragEvent) => {
      setOutside(isPointerOutsideSidebar(event.clientX, sidebarLeft));
    };
    window.addEventListener('dragover', onDragOver);
    return () => window.removeEventListener('dragover', onDragOver);
  }, [enabled, dragging, sidebarLeft]);

  return enabled && dragging && outside;
}
