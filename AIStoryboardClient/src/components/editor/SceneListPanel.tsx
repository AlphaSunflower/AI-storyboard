import { useRef, useState, useCallback } from 'react';
import type { SceneResponse } from '../../api/projects';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';
import { LiveOrb } from '../ui/live-orb';
import { Check, ArrowUpDown } from 'lucide-react';

/** 排序模式下的可拖拽卡片包裹 */
function SortableSceneCard({
  scene,
  isSelected,
  onSelect,
}: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: scene.id });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    position: 'relative',
    zIndex: isDragging ? 999 : 'auto' as unknown as number,
  };

  return (
    <div ref={setNodeRef} style={style} {...attributes}>
      {/* 拖拽把手 + 卡片 */}
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 4 }}>
        <div
          {...listeners}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 24,
            cursor: 'grab',
            color: 'var(--color-muted)',
            flexShrink: 0,
            touchAction: 'none',
          }}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="9" cy="6" r="1.5" />
            <circle cx="15" cy="6" r="1.5" />
            <circle cx="9" cy="12" r="1.5" />
            <circle cx="15" cy="12" r="1.5" />
            <circle cx="9" cy="18" r="1.5" />
            <circle cx="15" cy="18" r="1.5" />
          </svg>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <SceneCard
            scene={scene}
            isSelected={isSelected}
            onSelect={onSelect}
          />
        </div>
      </div>
    </div>
  );
}

export function SceneListPanel({ width }: { width: number }) {
  const { scenes, selectedSceneId, selectScene, addScene, currentProject, reorderScenes } =
    useProjectStore();

  const listRef = useRef<HTMLDivElement>(null);
  const prevCountRef = useRef(0);
  const [sortMode, setSortMode] = useState(false);

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

  // dnd-kit 传感器
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const oldIndex = scenes.findIndex(s => s.id === active.id);
    const newIndex = scenes.findIndex(s => s.id === over.id);
    if (oldIndex === -1 || newIndex === -1) return;
    const reordered = arrayMove(scenes, oldIndex, newIndex);
    // 乐观更新 + 持久化
    reorderScenes(reordered.map(s => s.id));
  }, [scenes, reorderScenes]);

  return (
    <div
      ref={listRef}
      style={{
        width: `${width}px`,
        minWidth: `${width}px`,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-canvas)',
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
        <div style={{ display: 'flex', gap: 6 }}>
          {scenes.length > 1 && (
            <button
              onClick={() => setSortMode(!sortMode)}
              style={{
                padding: '4px 14px',
                height: 28,
                fontSize: 12,
                borderRadius: 'var(--rounded-md)',
                border: sortMode ? '1px solid var(--color-primary)' : '1px solid var(--color-hairline)',
                background: sortMode ? 'var(--color-primary)' : 'white',
                cursor: 'pointer',
                color: sortMode ? 'white' : 'var(--color-body)',
                fontWeight: 500,
                transition: 'all 0.2s',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
              }}
            >
              {sortMode
                ? <><Check size={12} strokeWidth={2} /> 完成排序</>
                : <><ArrowUpDown size={12} strokeWidth={2} /> 排序</>}
            </button>
          )}
          {currentProject && !sortMode && (
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
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            <LiveOrb
              variant="webgl"
              colors={["#A33A18", "#D4682A", "#E8B45A"]}
              size={80}
            />
            <p
              style={{
                color: 'var(--color-muted-soft)',
                fontSize: 13,
                textAlign: 'center',
                animation: 'emptyGuideFloat 2.6s ease-in-out infinite',
              }}
            >
            <span style={{ display: 'block', marginBottom: 8 }}><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg></span>
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
        </div>
      ) : sortMode ? (
        /* 排序模式：拖拽列表 */
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={scenes.map(s => s.id)}
            strategy={verticalListSortingStrategy}
          >
            {scenes.map((scene) => (
              <SortableSceneCard
                key={scene.id}
                scene={scene}
                isSelected={selectedSceneId === scene.id}
                onSelect={() => selectScene(scene.id)}
              />
            ))}
          </SortableContext>
        </DndContext>
      ) : (
        /* 普通模式：场景卡片 */
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
