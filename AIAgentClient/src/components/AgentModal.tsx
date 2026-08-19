import { useRef, useEffect, useCallback } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../stores/agentStore';
import { StoryboardModal } from './modals/StoryboardModal';
import { AssetsModal } from './modals/AssetsModal';
import { ProjectModal } from './modals/ProjectModal';
import { SettingsModal } from './modals/SettingsModal';

const MODAL_MAP = {
  storyboard: StoryboardModal,
  assets: AssetsModal,
  project: ProjectModal,
  settings: SettingsModal,
} as const;

export function AgentModal() {
  const activeModal = useAgentStore((s) => s.activeModal);
  const setActiveModal = useAgentStore((s) => s.setActiveModal);

  const overlayRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const prevModal = useRef<typeof activeModal>(null);

  const close = useCallback(() => setActiveModal(null), [setActiveModal]);

  // 入场动画
  useGSAP(() => {
    if (activeModal && !prevModal.current) {
      gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2 });
      gsap.fromTo(panelRef.current, { x: 40, opacity: 0 }, { x: 0, opacity: 1, duration: 0.3, ease: 'back.out(1.1)' });
    }
  }, { dependencies: [activeModal], scope: rootRef });

  // 切换弹窗（已打开时切换类型）
  useGSAP(() => {
    if (activeModal && prevModal.current && activeModal !== prevModal.current) {
      gsap.fromTo(panelRef.current, { opacity: 0.5, scale: 0.98 }, { opacity: 1, scale: 1, duration: 0.2 });
    }
  }, { dependencies: [activeModal], scope: rootRef });

  useEffect(() => {
    prevModal.current = activeModal;
  }, [activeModal]);

  // Esc 关闭
  useEffect(() => {
    if (!activeModal) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [activeModal, close]);

  if (!activeModal) return null;

  const ModalContent = MODAL_MAP[activeModal];

  return (
    <div ref={rootRef} className="fixed inset-0 z-50 flex items-start justify-end">
      <div
        ref={overlayRef}
        onClick={close}
        className="absolute inset-0"
        style={{ background: 'rgba(20, 20, 19, 0.25)' }}
      />
      <div
        ref={panelRef}
        className="relative h-full overflow-y-auto"
        style={{
          width: 380,
          background: 'white',
          borderLeft: '1px solid var(--color-hairline)',
          boxShadow: '-4px 0 20px rgba(20,20,19,0.1)',
        }}
      >
        <div className="flex items-center justify-between px-4 py-3" style={{ borderBottom: '1px solid var(--color-hairline)' }}>
          <span className="text-sm font-semibold" style={{ color: 'var(--color-ink)' }}>
            {activeModal === 'storyboard' && '🎬 分镜'}
            {activeModal === 'assets' && '🖼️ 资产'}
            {activeModal === 'project' && '📁 项目'}
            {activeModal === 'settings' && '⚙️ 设置'}
          </span>
          <button
            onClick={close}
            className="text-sm"
            style={{ color: 'var(--color-muted)', background: 'none', border: 'none', cursor: 'pointer' }}
          >
            ✕
          </button>
        </div>
        <div className="p-4">
          <ModalContent />
        </div>
      </div>
    </div>
  );
}
