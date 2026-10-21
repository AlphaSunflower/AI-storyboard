import { useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { agentChatTokens as T } from './agentChatTokens';
import { useAbovePopoverPlacement } from './abovePopover';

/**
 * 菜单最大高度：向上可用的空间再大也不铺满整屏。
 *
 * <p>`useAbovePopoverPlacement` 给的是「输入卡到视口顶」的可用高度，对话一多就会变成
 * 一块顶到页面顶部的高列表（@ 一按就遮住整个消息区）。这里夹一层固定上限，超出时
 * 菜单内部滚动，键盘移动会把选中项滚进可视区。
 */
export const MENTION_MENU_MAX_HEIGHT = 360;

export type MentionGroup = 'source' | 'scene' | 'conversation';

const GROUP_LABEL: Record<MentionGroup, string> = {
  source: '资料',
  scene: '分镜',
  conversation: '对话',
};

export interface MentionMenuItem {
  id: string;
  group: MentionGroup;
  icon: ReactNode;
  /** 主文案：`分镜3` / `对话《分镜重做》` / `项目库文件` */
  title: string;
  description?: string;
  /** 除 title/description 之外的搜索词（含分组名，让「分镜」能筛出整组） */
  keywords?: string;
  /** 额度用满等情况下置灰：仍展示（可发现），不可执行并显示 disabledHint */
  disabled?: boolean;
  disabledHint?: string;
  /**
   * 说明行（如「还有 12 个会话未显示」）：只展示，不是候选项 ——
   * 不参与键盘导航、不计入分组数量、不可点击。
   */
  note?: boolean;
  run: () => void;
}

/** 菜单项是否命中输入的关键词：主副文案与关键词任一包含即可（与斜杠菜单同一口径）。 */
export function mentionItemMatches(item: MentionMenuItem, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [item.title, item.description ?? '', item.keywords ?? '', GROUP_LABEL[item.group]]
    .some((value) => value.toLowerCase().includes(q));
}

/**
 * `@` 引用资料面板：与 `ChatSlashMenu` 同款的向上浮层 + window 键盘捕获。
 *
 * <p>分组展示「资料（项目库文件 / 本地文件）／分镜（全部分镜 + 逐条）／对话（当前项目其它会话）」；
 * 数据全部来自内存（`projectStore.scenes`、`agentStore.conversations`），本组件不发请求。
 * 列表高度有上限（`MENTION_MENU_MAX_HEIGHT`），超出内部滚动，不随会话数量撑高。
 */
export function ChatMentionMenu({
  items, query, onClose, anchorRef,
}: {
  items: MentionMenuItem[];
  query: string;
  onClose: () => void;
  /** 定位锚点（输入卡）：浮层挂到 body，避免被浮窗 overflow 裁掉 */
  anchorRef: RefObject<HTMLElement | null>;
}) {
  const placement = useAbovePopoverPlacement(true, anchorRef, {
    gap: 8,
    align: 'stretch',
    minWidth: 320,
  });
  const visible = useMemo(
    () => items.filter((item) => mentionItemMatches(item, query)),
    [items, query],
  );
  /** 真候选（去掉说明行）：↑↓ / Enter 只在这条序列上移动 */
  const options = useMemo(() => visible.filter((item) => !item.note), [visible]);
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => { setActive(0); }, [query, items.length]);

  // 菜单有高度上限：键盘移动时要把选中项滚进可视区，否则高亮会跑到看不见的地方
  useEffect(() => {
    const node = listRef.current?.querySelector<HTMLElement>(`[data-mention-index="${active}"]`);
    node?.scrollIntoView?.({ block: 'nearest' });
  }, [active, visible.length]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
      if (options.length === 0) return;
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setActive((index) => (index + 1) % options.length);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        setActive((index) => (index - 1 + options.length) % options.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        const item = options[active];
        if (!item || item.disabled) return;
        event.preventDefault();
        item.run();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [options, active, onClose]);

  if (visible.length === 0 || !placement) return null;

  const counts = options.reduce<Record<string, number>>((accumulator, item) => {
    accumulator[item.group] = (accumulator[item.group] ?? 0) + 1;
    return accumulator;
  }, {});

  // 候选序号只数真候选：说明行不占 ↑↓ 的一格
  let optionCursor = -1;

  const groupHeader = (group: MentionGroup) => (
    <div style={{
      padding: '6px 10px 2px', fontSize: 11, fontWeight: 600,
      color: T.textCaption, letterSpacing: 0.4,
    }}>
      {GROUP_LABEL[group]}（{counts[group] ?? 0}）
    </div>
  );

  return createPortal(
    <div className="chat-mention-menu" role="listbox" aria-label="引用资料" ref={listRef} style={{
      position: 'fixed', bottom: placement.bottom, left: placement.left,
      width: placement.width,
      maxHeight: Math.min(placement.maxHeight, MENTION_MENU_MAX_HEIGHT),
      overflowY: 'auto',
      background: 'var(--color-panel)', border: `1px solid ${T.border}`,
      borderRadius: 14, boxShadow: '0 8px 28px rgba(0,0,0,0.14)',
      padding: 6, zIndex: 1100, display: 'flex', flexDirection: 'column', gap: 2,
    }}>
      {visible.map((item, index) => {
        const previous = index > 0 ? visible[index - 1] : null;
        const showHeader = !previous || previous.group !== item.group;
        if (item.note) {
          return (
            <div key={item.id}>
              {showHeader && groupHeader(item.group)}
              <div style={{
                padding: '4px 10px 6px', fontSize: 11, lineHeight: 1.5,
                color: T.textCaption,
              }}>
                {item.title}
              </div>
            </div>
          );
        }
        optionCursor += 1;
        const optionIndex = optionCursor;
        return (
          <div key={item.id}>
            {showHeader && groupHeader(item.group)}
            <button
              type="button"
              role="option"
              data-mention-index={optionIndex}
              aria-selected={optionIndex === active}
              onMouseEnter={() => setActive(optionIndex)}
              onClick={() => { if (!item.disabled) item.run(); }}
              disabled={item.disabled}
              style={{
                display: 'flex', alignItems: 'center', gap: 10, width: '100%',
                padding: '8px 10px', border: 'none', borderRadius: 10, textAlign: 'left',
                background: optionIndex === active && !item.disabled ? T.hover : 'transparent',
                color: item.disabled ? T.textCaption : T.ink,
                cursor: item.disabled ? 'not-allowed' : 'pointer',
              }}
            >
              <span style={{ display: 'flex', flexShrink: 0, color: item.disabled ? T.textCaption : T.brand }}>
                {item.icon}
              </span>
              <span style={{ flex: 1, minWidth: 0 }}>
                <span style={{
                  display: 'block', fontSize: 13, fontWeight: 600,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>
                  {item.title}
                </span>
                {(item.disabled ? (item.disabledHint ?? item.description) : item.description) && (
                  <span style={{
                    display: 'block', fontSize: 12, color: T.textCaption, marginTop: 1,
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>
                    {item.disabled ? (item.disabledHint ?? item.description) : item.description}
                  </span>
                )}
              </span>
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
}
