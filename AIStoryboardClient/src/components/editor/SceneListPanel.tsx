import { useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';

export function SceneListPanel({ width }: { width: number }) {
  const { scenes, selectedSceneId, selectScene, addScene, currentProject } =
    useProjectStore();

  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);

  // 新增分镜时（AI 生成脚本 / 手动添加），新卡片依次浮入；初始加载同样生效
  useGSAP(() => {
    const prev = prevCountRef.current;
    const current = scenes.length;
    prevCountRef.current = current;
    if (current <= prev) return; // 删除或无变化不动画
    const container = listRef.current;
    if (!container) return;
    const cards = Array.from(container.children).slice(-(current - prev));
    if (cards.length === 0) return;
    gsap.from(cards, {
      y: 18,
      opacity: 0,
      scale: 0.98,
      duration: 0.38,
      ease: 'power2.out',
      stagger: 0.05,
      onComplete: () => {
        // 清除残留 transform：卡片作为 containing block 会让内部 fixed 弹窗（完善图片/视频）错位被遮挡
        cards.forEach((el) => gsap.set(el, { clearProps: 'transform' }));
      },
    });
  }, { dependencies: [scenes.length], scope: listRef });

  return (
    <div
      ref={listRef}
      style={{
        width: `${width}px`,
        minWidth: `${width}px`,
        borderRight: '1px solid var(--color-hairline)',
        background: 'white',
        padding: 'var(--space-md)',
        overflowY: 'auto',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Header row */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 12,
        }}
      >
        <h2
          style={{
            font: 'var(--text-title-sm)',
            color: 'var(--color-ink)',
            margin: 0,
          }}
        >
          分镜列表
        </h2>
        {currentProject && (
          <button
            onClick={() => addScene(currentProject.id)}
            style={{
              padding: '4px 14px',
              height: 28,
              fontSize: 12,
              borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)',
              background: 'white',
              cursor: 'pointer',
              color: 'var(--color-body)',
              fontWeight: 500,
            }}
          >
            + 添加
          </button>
        )}
      </div>

      {/* Empty state */}
      {scenes.length === 0 ? (
        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <p
            style={{
              color: 'var(--color-muted-soft)',
              fontSize: 13,
              textAlign: 'center',
              padding: 'var(--space-lg)',
              animation: 'emptyGuideFloat 2.6s ease-in-out infinite',
            }}
          >
            <span style={{ fontSize: 26, display: 'block', marginBottom: 8 }}>🎬</span>
            暂无分镜
            <br />
            请输入剧本并点击"生成分镜脚本"
            <style>{`
              @keyframes emptyGuideFloat {
                0%, 100% { transform: translateY(0); opacity: 0.75; }
                50% { transform: translateY(-6px); opacity: 1; }
              }
            `}</style>
          </p>
        </div>
      ) : (
        /* Scene cards */
        scenes.map((scene) => (
          <SceneCard
            key={scene.id}
            scene={scene}
            isSelected={selectedSceneId === scene.id}
            onSelect={() => selectScene(scene.id)}
          />
        ))
      )}
    </div>
  );
}
