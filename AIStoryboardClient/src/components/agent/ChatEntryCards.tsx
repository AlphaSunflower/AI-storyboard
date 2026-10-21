import { ArrowUpRight, Frame, Library, PenLine } from 'lucide-react';
import type { ChatAppKey } from './apps/chatAppDefinitions';
import type { CanvasModePhase } from './apps/ChatAppActions';
import '../../styles/chat-entry-cards.css';

export interface ChatEntryCardsProps {
  /** 是否已有当前项目：编辑器离不开项目上下文，缺项目时只做提示不做跳转。 */
  hasProject: boolean;
  onOpen: (key: ChatAppKey) => void;
  /** 画布绑定/编辑中时，画布入口不再重复开新标签页。 */
  canvasPhase?: CanvasModePhase;
  /** compact：手机端三列小卡，只留图标和名称。 */
  variant?: 'full' | 'compact';
}

const CANVAS_PHASE_COPY: Record<Exclude<CanvasModePhase, 'natural'>, string> = {
  binding: '正在准备画布，稍后从这里进入',
  locked: '当前已在画布编辑模式中',
  exit_pending: '本轮结束后离开画布编辑模式',
};

/**
 * 首次对话空态的三个工作台入口：画布 / 编辑器 / 项目库。
 *
 * <p>它们与顶栏、快捷键走同一个 `onOpen`，不额外定义导航规则；这里只负责
 * 把「空会话还能去哪儿」讲清楚，并把不可用状态（无项目、画布绑定中）说明白。
 */
export function ChatEntryCards({
  hasProject,
  onOpen,
  canvasPhase = 'natural',
  variant = 'full',
}: ChatEntryCardsProps) {
  const items: Array<{
    key: ChatAppKey;
    title: string;
    description: string;
    icon: React.ReactNode;
    disabled: boolean;
    keyshortcuts: string;
  }> = [
    {
      key: 'canvas',
      title: '画布',
      description: canvasPhase === 'natural'
        ? '节点和连线，批量跑完整条流程'
        : CANVAS_PHASE_COPY[canvasPhase],
      icon: <Frame size={20} strokeWidth={1.8} />,
      disabled: canvasPhase !== 'natural',
      keyshortcuts: 'Control+Shift+8 Meta+Shift+8',
    },
    {
      key: 'editor',
      title: '编辑器',
      description: hasProject
        ? '逐镜修改脚本、画面与视频'
        : '选择项目后，逐镜精修',
      icon: <PenLine size={20} strokeWidth={1.8} />,
      disabled: false,
      keyshortcuts: 'Control+Shift+7 Meta+Shift+7',
    },
    {
      key: 'library',
      title: '项目库',
      description: '素材、文档与成片集中管理',
      icon: <Library size={20} strokeWidth={1.8} />,
      disabled: false,
      keyshortcuts: 'Control+Shift+9 Meta+Shift+9',
    },
  ];

  return (
    <nav
      className={`chat-entry-cards${variant === 'compact' ? ' chat-entry-cards--compact' : ''}`}
      aria-label="创作工作台入口"
    >
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          className="chat-entry-card"
          data-entry={item.key}
          disabled={item.disabled}
          aria-keyshortcuts={item.keyshortcuts}
          onClick={() => onOpen(item.key)}
        >
          <span className="chat-entry-card__top">
            <span className="chat-entry-card__icon" aria-hidden="true">{item.icon}</span>
            <span className="chat-entry-card__arrow" aria-hidden="true">
              <ArrowUpRight size={16} strokeWidth={1.8} />
            </span>
          </span>
          <span className="chat-entry-card__text">
            <span className="chat-entry-card__title">{item.title}</span>
            <span className="chat-entry-card__desc">{item.description}</span>
          </span>
        </button>
      ))}
    </nav>
  );
}
