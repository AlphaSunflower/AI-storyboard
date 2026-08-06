import { useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';

const coralColor = '#FF6B6B';

export function ProjectHistoryPanel() {
  const { projects, currentProject, loadProject } = useProjectStore();
  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);

  // B6: 项目列表入场——首屏/新增项 stagger 浮现（只动画新出现的项，避免全部重播）
  useGSAP(() => {
    const prev = prevCountRef.current;
    const current = projects.length;
    prevCountRef.current = current;
    const container = listRef.current;
    if (!container || current <= prev) return;
    const items = Array.from(container.children).slice(-(current - prev));
    if (items.length === 0) return;
    gsap.from(items, {
      x: -10,
      opacity: 0,
      duration: 0.3,
      ease: 'power2.out',
      stagger: 0.04,
      onComplete: () => {
        // 清除残留 transform，避免影响内部 fixed 弹窗
        items.forEach((el) => gsap.set(el, { clearProps: 'transform' }));
      },
    });
  }, { dependencies: [projects.length], scope: listRef });

  if (projects.length === 0) {
    return null;
  }

  return (
    <div
      ref={listRef}
      style={{
        overflowY: 'auto',
        flex: 1,
        minHeight: 0,
      }}
    >
      {projects.map((p) => {
        const isActive = currentProject?.id === p.id;

        return (
          <div
            key={p.id}
            onClick={() => loadProject(p.id)}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              background: isActive ? `${coralColor}12` : 'transparent',
              cursor: 'pointer',
              fontSize: 13,
              color: isActive ? coralColor : 'var(--color-body)',
              fontWeight: isActive ? 600 : 400,
              borderLeft: isActive ? `3px solid ${coralColor}` : '3px solid transparent',
              transition: 'background 0.12s ease',
            }}
          >
            <span
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                flex: 1,
                marginRight: 8,
              }}
            >
              {p.name}
            </span>
            {p.status === 'draft' && (
              <span
                style={{
                  fontSize: 10,
                  color: 'var(--color-muted)',
                  background: 'var(--color-canvas-muted)',
                  padding: '1px 6px',
                  borderRadius: 8,
                  flexShrink: 0,
                }}
              >
                草稿
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
