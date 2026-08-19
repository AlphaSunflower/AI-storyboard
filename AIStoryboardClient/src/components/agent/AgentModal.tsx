import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import type { ReactNode } from 'react';

interface AgentModalProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  width?: number;
}

export function AgentModal({ title, onClose, children, width = 500 }: AgentModalProps) {
  const [mounted, setMounted] = useState(true);
  const [closing, setClosing] = useState(false);
  const overlayRef = useRef<HTMLDivElement>(null);
  const modalRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  // 关闭请求：标记 closing，退场动画完成后卸载
  const handleClose = () => {
    if (!closing) setClosing(true);
  };

  // 入场动画
  useGSAP(() => {
    if (mounted && !closing) {
      gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2, ease: 'power2.out' });
      gsap.fromTo(
        modalRef.current,
        { y: 40, opacity: 0 },
        { y: 0, opacity: 1, duration: 0.3, ease: 'back.out(1.2)' }
      );
    }
  }, { dependencies: [mounted], scope: rootRef, revertOnUpdate: true });

  // 退场动画
  useGSAP(() => {
    if (closing && mounted) {
      gsap.to(overlayRef.current, { opacity: 0, duration: 0.2, ease: 'power2.in' });
      gsap.to(modalRef.current, {
        y: 40,
        opacity: 0,
        duration: 0.22,
        ease: 'power2.in',
        onComplete: () => {
          setMounted(false);
          setClosing(false);
          onClose();
        },
      });
    }
  }, { dependencies: [closing], scope: rootRef, revertOnUpdate: true });

  // Esc 关闭
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  if (!mounted) return null;

  return (
    <div ref={rootRef} className="fixed inset-0 z-50 flex items-center justify-center">
      {/* 遮罩 */}
      <div
        ref={overlayRef}
        onClick={handleClose}
        className="absolute inset-0"
        style={{ background: 'rgba(0,0,0,0.4)' }}
      />
      {/* 弹窗主体 */}
      <div
        ref={modalRef}
        className="relative flex flex-col max-h-[85vh]"
        style={{
          width,
          background: 'var(--color-canvas)',
          borderRadius: 12,
          border: '1px solid var(--color-hairline)',
          boxShadow: '0 8px 32px rgba(20,20,19,0.16)',
        }}
      >
        {/* 标题栏 */}
        <div
          className="flex items-center justify-between shrink-0 px-5 py-4"
          style={{ borderBottom: '1px solid var(--color-hairline)' }}
        >
          <h2 className="text-base font-semibold" style={{ color: 'var(--color-ink)' }}>{title}</h2>
          <button
            onClick={handleClose}
            className="w-7 h-7 flex items-center justify-center rounded-md hover:opacity-70 transition-opacity text-lg"
            style={{ color: 'var(--color-ink)' }}
          >
            ✕
          </button>
        </div>
        {/* 内容区 */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {children}
        </div>
      </div>
    </div>
  );
}
