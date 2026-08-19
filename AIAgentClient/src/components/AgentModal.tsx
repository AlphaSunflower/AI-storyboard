import { useRef, useEffect, useCallback } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '@/stores/agentStore';
import { StoryboardModal } from './modals/StoryboardModal';
import { AssetsModal } from './modals/AssetsModal';
import { ProjectModal } from './modals/ProjectModal';
import { X, Film, Image, Folder } from 'lucide-react';

const MODAL_MAP = {
  storyboard: { component: StoryboardModal, icon: Film, label: '分镜' },
  assets: { component: AssetsModal, icon: Image, label: '产出素材' },
  project: { component: ProjectModal, icon: Folder, label: '项目' },
} as const;

export function AgentModal() {
  const activeModal = useAgentStore((s) => s.activeModal);
  const setActiveModal = useAgentStore((s) => s.setActiveModal);
  const overlayRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const prevModal = useRef<typeof activeModal>(null);
  const close = useCallback(() => setActiveModal(null), [setActiveModal]);

  useGSAP(() => {
    if (activeModal && !prevModal.current) {
      gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2 });
      gsap.fromTo(panelRef.current, { x: 36, opacity: 0 }, { x: 0, opacity: 1, duration: 0.25, ease: 'power3.out' });
    }
  }, { dependencies: [activeModal], scope: rootRef });

  useGSAP(() => {
    if (activeModal && prevModal.current && activeModal !== prevModal.current) {
      gsap.fromTo(panelRef.current, { opacity: 0.6 }, { opacity: 1, duration: 0.12 });
    }
  }, { dependencies: [activeModal], scope: rootRef });

  useEffect(() => { prevModal.current = activeModal; }, [activeModal]);
  useEffect(() => {
    if (!activeModal) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [activeModal, close]);

  if (!activeModal) return null;
  const modal = MODAL_MAP[activeModal];
  const ModalContent = modal.component;
  const Icon = modal.icon;

  return (
    <div ref={rootRef} className="fixed inset-0 z-50 flex items-start justify-end">
      <div ref={overlayRef} onClick={close} className="absolute inset-0" style={{ background: 'rgba(20,20,19,0.2)' }} />
      <div ref={panelRef} className="relative h-full overflow-y-auto"
        style={{ width: 380, background: 'var(--color-canvas)', borderLeft: '1px solid var(--color-border)', boxShadow: '-6px 0 24px rgba(20,20,19,0.06)' }}>
        <div className="flex items-center justify-between px-7 py-4 sticky top-0 z-10"
          style={{ borderBottom: '1px solid var(--color-border-light)', background: 'var(--color-canvas)' }}>
          <div className="flex items-center gap-2.5">
            <Icon size={17} style={{ color: 'var(--color-primary)' }} />
            <span className="text-[16px] font-medium" style={{ color: 'var(--color-ink)' }}>{modal.label}</span>
          </div>
          <button onClick={close} className="p-1.5 rounded-lg transition-colors hover:bg-[var(--color-surface-soft)]"
            style={{ color: 'var(--color-muted)', background: 'none', border: 'none', cursor: 'pointer' }}>
            <X size={17} />
          </button>
        </div>
        <div className="p-7"><ModalContent /></div>
      </div>
    </div>
  );
}
