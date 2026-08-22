import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { Image as ImageIcon, Video, MessageSquare, FileText, Zap } from 'lucide-react';
import { useProjectStore } from '../../stores/projectStore';
import { useAgentStore } from '../../stores/agentStore';
import { buildTasks, type TaskItem } from './taskItems';

/**
 * 任务中心悬浮球：右下角 ☾ 智能体球正上方（bottom 88），聚合展示进行中的任务——
 * 分镜图片/视频生成、脚本生成、智能体交流、智能体视频生成。
 * 纯展示组件：直接订阅两个 store 的瞬态状态，无自己的任务记录层（只显示进行中）。
 */

const ICONS: Record<TaskItem['kind'], ReactNode> = {
  image: <ImageIcon size={15} strokeWidth={1.8} />,
  video: <Video size={15} strokeWidth={1.8} />,
  agent: <MessageSquare size={15} strokeWidth={1.8} />,
  script: <FileText size={15} strokeWidth={1.8} />,
};

export function TaskFab() {
  const generatingImage = useProjectStore((s) => s.generatingImage);
  const generatingVideo = useProjectStore((s) => s.generatingVideo);
  const videoProgress = useProjectStore((s) => s.videoProgress);
  const scenes = useProjectStore((s) => s.scenes);
  const scriptStatus = useProjectStore((s) => s.scriptGenerationStatus);
  const selectScene = useProjectStore((s) => s.selectScene);
  const streaming = useAgentStore((s) => s.streaming);
  const workflowHint = useAgentStore((s) => s.workflowHint);
  const agentVideoTask = useAgentStore((s) => s.agentVideoTask);
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);

  // 弹窗开关：单一 open 布尔（原 mounted+closing 双状态机会卡死：windowOpen effect 在面板
  // 未挂载时也 setClosing(true)，而退场钩子因 !mounted 提前 return 不复位 → closing 恒 true，
  // toggle 的 if(!closing) 恒空转，面板收不起）
  const [open, setOpen] = useState(false);

  const rootRef = useRef<HTMLDivElement>(null);
  const fabRef = useRef<HTMLButtonElement>(null);
  const badgeRef = useRef<HTMLSpanElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);

  const tasks = useMemo<TaskItem[]>(
    () => buildTasks({
      generatingImage,
      generatingVideo,
      videoProgress,
      scenes,
      scriptStatus,
      streaming,
      workflowHint,
      agentVideoTask,
    }),
    [generatingImage, generatingVideo, videoProgress, scenes, scriptStatus, streaming, workflowHint, agentVideoTask],
  );

  const count = tasks.length;

  // 悬浮球入场：弹性放大（与 ☾ 智能体球一致的 back.out(2)）
  useGSAP(() => {
    if (!fabRef.current) return;
    gsap.fromTo(fabRef.current, { scale: 0, opacity: 0 }, { scale: 1, opacity: 1, duration: 0.5, ease: 'back.out(2)' });
  }, { scope: fabRef });

  // 徽标数字变化：轻微弹跳
  useGSAP(() => {
    if (badgeRef.current) {
      gsap.fromTo(badgeRef.current, { scale: 1.5 }, { scale: 1, duration: 0.3, ease: 'back.out(3)' });
    }
  }, { dependencies: [count], scope: rootRef, revertOnUpdate: true });

  // 弹窗入场
  useGSAP(() => {
    if (!open) return;
    const panel = rootRef.current?.querySelector('[data-task-panel]');
    if (panel) gsap.fromTo(panel, { y: 12, opacity: 0 }, { y: 0, opacity: 1, duration: 0.32, ease: 'back.out(1.2)' });
  }, { dependencies: [open], scope: rootRef, revertOnUpdate: true });

  // 新增任务项 stagger 入场（只动画新增的尾部项）
  useGSAP(() => {
    const prev = prevCountRef.current;
    const current = count;
    prevCountRef.current = current;
    if (current <= prev || !listRef.current) return;
    const items = Array.from(listRef.current.children).slice(-(current - prev));
    gsap.from(items, { y: 10, opacity: 0, scale: 0.98, duration: 0.3, ease: 'power2.out', stagger: 0.04 });
  }, { dependencies: [count], scope: listRef, revertOnUpdate: true });

  // 互斥：智能体抽屉打开 → 关闭任务弹窗（防遮挡）
  useEffect(() => {
    if (windowOpen) setOpen(false);
  }, [windowOpen]);

  // 点击弹窗外部关闭
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onDown);
    return () => document.removeEventListener('pointerdown', onDown);
  }, [open]);

  const toggle = () => setOpen((v) => !v);

  const handleItemClick = (t: TaskItem) => {
    setOpen(false);
    if (t.sceneId) {
      selectScene(t.sceneId); // 分镜任务：跳转选中分镜
    } else if (t.kind === 'agent' || t.id === 'agent-video') {
      setWindowOpen(true);    // 智能体任务：打开智能体抽屉
    }
  };

  return (
    <div ref={rootRef}>
      <style>{'@keyframes tc-spin { to { transform: rotate(360deg); } }'}</style>
      <button
        ref={fabRef}
        onClick={toggle}
        title="任务列表"
        style={{
          position: 'fixed',
          right: 24,
          bottom: 88, // ☾ 智能体球（bottom 24）正上方，12px 间距
          width: 52,
          height: 52,
          borderRadius: '50%',
          border: 'none',
          background: 'var(--color-primary)',
          color: '#fff',
          fontSize: 20,
          cursor: 'pointer',
          boxShadow: '0 4px 16px rgba(204, 120, 92, 0.45)',
          zIndex: 90,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transformOrigin: 'center',
        }}
      >
        <Zap size={20} strokeWidth={2} />
        {count > 0 && (
          <span
            ref={badgeRef}
            style={{
              position: 'absolute',
              top: -4,
              right: -4,
              minWidth: 18,
              height: 18,
              borderRadius: 9,
              background: '#e2544b',
              color: '#fff',
              fontSize: 11,
              lineHeight: '18px',
              textAlign: 'center',
              padding: '0 4px',
              boxShadow: '0 2px 6px rgba(226, 84, 75, 0.5)',
            }}
          >
            {count}
          </span>
        )}
      </button>

      {open && (
        <div
          data-task-panel
          style={{
            position: 'fixed',
            right: 24,
            bottom: 152, // 球（88+52）上方 12px
            width: 320,
            maxHeight: 420,
            overflowY: 'auto',
            background: '#faf9f5',
            border: '1px solid rgba(20, 20, 19, 0.08)',
            borderRadius: 12,
            boxShadow: '0 12px 32px rgba(20, 20, 19, 0.18)',
            padding: 8,
            zIndex: 91,
            transformOrigin: 'bottom right',
          }}
        >
          <div style={{ fontSize: 13, fontWeight: 600, color: '#141413', padding: '8px 10px 6px' }}>
            进行中的任务
          </div>
          {count === 0 ? (
            <div style={{ fontSize: 13, color: '#8a857c', padding: '18px 10px', textAlign: 'center' }}>
              当前没有进行中的任务
            </div>
          ) : (
            <div ref={listRef}>
              {tasks.map((t) => (
                <div
                  key={t.id}
                  onClick={() => handleItemClick(t)}
                  onMouseEnter={(e) => { e.currentTarget.style.background = '#efe9de'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
                  style={{
                    display: 'flex',
                    gap: 10,
                    alignItems: 'center',
                    padding: '9px 10px',
                    borderRadius: 8,
                    cursor: 'pointer',
                    transition: 'background 0.15s',
                  }}
                >
                  <span style={{ display: 'inline-flex', alignItems: 'center' }}>{ICONS[t.kind]}</span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, color: '#141413', fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {t.title}
                    </div>
                    {t.kind === 'video' && t.sceneId ? (
                      <div style={{ height: 4, background: 'rgba(20, 20, 19, 0.08)', borderRadius: 2, marginTop: 6, overflow: 'hidden' }}>
                        <div style={{ height: '100%', width: `${t.progress ?? 0}%`, background: 'var(--color-primary)', borderRadius: 2, transition: 'width 0.4s' }} />
                      </div>
                    ) : t.hint ? (
                      <div style={{ fontSize: 12, color: '#8a857c', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {t.hint}
                      </div>
                    ) : null}
                  </div>
                  {/* 运行中 spinner（CSS 装饰动画，模式 9） */}
                  <span
                    style={{
                      width: 14,
                      height: 14,
                      flexShrink: 0,
                      border: '2px solid rgba(204, 120, 92, 0.25)',
                      borderTopColor: 'var(--color-primary)',
                      borderRadius: '50%',
                      animation: 'tc-spin 0.8s linear infinite',
                    }}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
