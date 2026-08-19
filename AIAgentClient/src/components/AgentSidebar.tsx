import { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../stores/agentStore';
import { cn } from '../lib/utils';
import {
  Plus, Film, Image, Folder, MessageSquare, ChevronDown,
  ChevronRight, Trash2, LogOut, Moon,
} from 'lucide-react';

/** 侧栏默认/边界宽度(可左右拉伸) */
const SIDEBAR_MIN = 200;
const SIDEBAR_MAX = 400;
const SIDEBAR_DEFAULT = 240;

export function AgentSidebar() {
  const {
    activeModal, setActiveModal, historyExpanded, setHistoryExpanded,
    conversations, activeConversationId, selectConversation,
    createConversation, deleteConversation, waitingHumanInput, streaming, projectId,
  } = useAgentStore();

  const activeConvs = conversations.filter((c) => c.status !== 'archived');
  const rootRef = useRef<HTMLDivElement>(null);
  const itemsRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  // 侧栏宽度:局部 state,刷新回默认(不持久化)
  const [width, setWidth] = useState(SIDEBAR_DEFAULT);
  const draggingRef = useRef(false);
  // 当前登录用户(登录页持久化的 user JSON)
  const user = useMemo(() => {
    try { return JSON.parse(localStorage.getItem('user') ?? 'null') as { userId?: string; displayName?: string } | null; } catch { return null; }
  }, []);
  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    navigate('/login');
  };

  // 拖拽拉伸:onMouseDown 记录起点,onMouseMove 更新宽度,onMouseUp 结束
  const onDragStart = useCallback((e: React.MouseEvent) => {
    draggingRef.current = true;
    e.preventDefault();
    const startX = e.clientX;
    const startW = width;
    const onMove = (ev: MouseEvent) => {
      if (!draggingRef.current) return;
      setWidth(Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, startW + (ev.clientX - startX))));
    };
    const onUp = () => {
      draggingRef.current = false;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, [width]);

  useEffect(() => () => { draggingRef.current = false; }, []);

  useGSAP(() => {
    if (!rootRef.current) return;
    const els = rootRef.current.querySelectorAll('[data-nav]');
    gsap.fromTo(els, { x: -10, opacity: 0 }, { x: 0, opacity: 1, duration: 0.25, stagger: 0.03, ease: 'power2.out' });
  }, { scope: rootRef });

  useGSAP(() => {
    if (!itemsRef.current || !historyExpanded) return;
    const els = itemsRef.current.querySelectorAll('[data-conv]');
    gsap.fromTo(els, { y: 4, opacity: 0 }, { y: 0, opacity: 1, duration: 0.12, stagger: 0.02, ease: 'power2.out' });
  }, { dependencies: [historyExpanded, activeConvs.length], scope: itemsRef });

  return (
    <>
      <div ref={rootRef} className="flex flex-col select-none shrink-0"
        style={{ width, minWidth: SIDEBAR_MIN, maxWidth: SIDEBAR_MAX, background: 'var(--color-surface-soft)' }}>

        <div className="flex items-center gap-3 px-6 py-6" data-nav style={{ borderBottom: '1px solid var(--color-border-light)' }}>
          <Moon size={22} style={{ color: 'var(--color-primary)' }} />
          <span className="font-semibold text-[18px]" style={{ color: 'var(--color-ink)' }}>Moon 智能体</span>
        </div>

        <div className="px-5 pt-5 pb-3" data-nav>
          <button onClick={() => createConversation()} disabled={!projectId}
            className="w-full flex items-center justify-center gap-2 px-4 py-3.5 text-[16px] font-medium rounded-[10px] transition-all hover:brightness-110 active:scale-[0.98]"
            style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)', opacity: projectId ? 1 : 0.4 }}>
            <Plus size={17} /> 新对话
          </button>
        </div>

        <nav className="px-4 py-1.5 flex flex-col gap-1">
          {[
            { icon: Film, label: '分镜', modal: 'storyboard' as const },
            { icon: Image, label: '产出素材', modal: 'assets' as const },
            { icon: Folder, label: '项目', modal: 'project' as const },
          ].map((item) => (
            <button key={item.label} data-nav
              onClick={() => setActiveModal(activeModal === item.modal ? null : item.modal)}
              className={cn('flex items-center gap-3 px-4 py-3 text-[16px] rounded-[10px] transition-all',
                activeModal === item.modal ? 'bg-[var(--color-surface-card)] text-[var(--color-ink)]'
                  : 'text-[var(--color-body)] hover:text-[var(--color-ink)] hover:bg-[var(--color-surface-card)]',
              )}>
              <item.icon size={17} strokeWidth={1.8} />
              {item.label}
            </button>
          ))}
        </nav>

        <div className="mx-5 my-4" style={{ borderTop: '1px solid var(--color-border-light)' }} data-nav />

        <button data-nav onClick={() => setHistoryExpanded(!historyExpanded)}
          className="flex items-center gap-3 mx-4 px-4 py-3 text-[16px] rounded-[10px] transition-colors text-[var(--color-body)] hover:text-[var(--color-ink)] hover:bg-[var(--color-surface-card)]">
          <MessageSquare size={17} strokeWidth={1.8} />
          <span className="flex-1 text-left">历史</span>
          {historyExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </button>

        {historyExpanded && (
          <div ref={itemsRef} className="flex-1 overflow-y-auto mx-4 mb-1 min-h-0">
            {activeConvs.length === 0 && <p className="px-4 py-3 text-[15px]" style={{ color: 'var(--color-muted)' }}>暂无对话</p>}
            {activeConvs.map((c) => {
              const isActive = c.id === activeConversationId;
              return (
                <div key={c.id} data-conv
                  onClick={() => { if (!waitingHumanInput && !streaming) selectConversation(c.id); }}
                  className={cn('group flex items-center gap-2.5 px-4 py-3 rounded-[10px] cursor-pointer mb-1 transition-all',
                    isActive ? 'bg-[var(--color-surface-card)]' : 'hover:bg-[var(--color-surface-card)]',
                    waitingHumanInput && !isActive && 'opacity-40 pointer-events-none',
                  )}>
                  <div className="flex-1 text-[15px] truncate"
                    style={{ color: isActive ? 'var(--color-ink)' : 'var(--color-body)', fontWeight: isActive ? 500 : 400 }}>
                    {c.title}
                  </div>
                  <button onClick={(e) => { e.stopPropagation(); deleteConversation(c.id); }}
                    className="opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity p-1"
                    style={{ color: 'var(--color-muted)', background: 'none', border: 'none', cursor: 'pointer' }}>
                    <Trash2 size={13} />
                  </button>
                </div>
              );
            })}
          </div>
        )}

        {/* 左下角:登录用户信息 + 退出登录(无设置弹窗) */}
        <div className="mt-auto px-4 pb-5" data-nav>
          <div className="flex items-center gap-3 px-4 py-3" style={{ borderTop: '1px solid var(--color-border-light)', paddingTop: 14 }}>
            <div className="flex items-center justify-center shrink-0" style={{
              width: 36, height: 36, borderRadius: '50%',
              background: 'var(--color-surface-card)', color: 'var(--color-primary)',
              fontSize: 16, fontWeight: 600,
            }}>
              {(user?.displayName ?? '?').slice(0, 1).toUpperCase()}
            </div>
            <span className="flex-1 min-w-0 truncate text-[15px]" style={{ color: 'var(--color-ink)' }}>
              {user?.displayName ?? '未登录'}
            </span>
            <button onClick={handleLogout} title="退出登录"
              className="p-2 rounded-lg transition-colors hover:bg-[var(--color-surface-card)]"
              style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer' }}>
              <LogOut size={15} />
            </button>
          </div>
        </div>
      </div>

      {/* 拖拽把手:4px, hover 变主色 */}
      <div onMouseDown={onDragStart} title="拖拽调整宽度"
        style={{
          width: 4, flexShrink: 0, cursor: 'col-resize',
          background: 'transparent', transition: 'background 0.15s',
        }}
        className="hover:bg-[var(--color-primary)]" />
    </>
  );
}
