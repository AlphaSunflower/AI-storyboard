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
import { SceneOutlineRow } from '../scene/SceneOutlineRow';
import { LiveOrb } from '../ui/live-orb';
import { Check, ArrowUpDown, List, LayoutGrid, Plus, GripVertical } from 'lucide-react';

/** 排序模式下的可拖拽行包裹（大纲视图：拖行即可重排，不需要额外进入排序模式） */
function SortableOutlineRow({
  scene,
  isSelected,
  onSelect,
}: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: scene.id });

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        display: 'flex',
        alignItems: 'center',
        gap: 0,
        zIndex: isDragging ? 999 : undefined,
        position: 'relative',
      }}
      {...attributes}
    >
      <div
        {...listeners}
        aria-label={`拖动排序分镜 ${scene.sceneNumber}`}
        style={{
          display: 'grid',
          placeItems: 'center',
          width: 13,
          flexShrink: 0,
          color: 'var(--color-muted-soft)',
          cursor: 'grab',
          touchAction: 'none',
        }}
      >
        <GripVertical size={13} strokeWidth={1.7} aria-hidden="true" />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <SceneOutlineRow
          scene={scene}
          isSelected={isSelected}
          onSelect={onSelect}
          dragging={isDragging}
        />
      </div>
    </div>
  );
}

type SceneListView = 'outline' | 'card';

const VIEW_STORAGE_KEY = 'sceneListView';

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
          aria-label={`拖动排序分镜 ${scene.sceneNumber}`}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 20,
            cursor: 'grab',
            color: 'var(--color-muted-soft)',
            flexShrink: 0,
            touchAction: 'none',
          }}
        >
          <GripVertical size={15} strokeWidth={1.7} aria-hidden="true" />
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
  // 视图切换：大纲（默认，一屏 20+ 镜）/ 卡片（旧视图，保留重命名等全部操作）
  const [viewMode, setViewMode] = useState<SceneListView>(() => {
    try {
      return window.localStorage.getItem(VIEW_STORAGE_KEY) === 'card' ? 'card' : 'outline';
    } catch {
      return 'outline';
    }
  });

  const switchView = (next: SceneListView) => {
    setViewMode(next);
    try {
      window.localStorage.setItem(VIEW_STORAGE_KEY, next);
    } catch {
      // 隐私模式下 localStorage 不可用：只在本次会话生效
    }
  };

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
      className="scene-panel"
      style={{ width: `${width}px`, minWidth: `${width}px` }}
    >
      {/* Header row */}
      <div className="scene-panel__head">
        <div className="scene-panel__title-wrap">
          <h2 className="scene-panel__title">分镜列表</h2>
          <span className="scene-panel__count">{scenes.length}</span>
        </div>
        <div className="scene-panel__tools">
          {scenes.length > 0 && !sortMode && (
            <div className="segmented" role="group" aria-label="分镜视图">
              <button
                type="button"
                className="segmented__btn"
                aria-pressed={viewMode === 'outline'}
                onClick={() => switchView('outline')}
                title="大纲视图：一行一镜，密度高"
              >
                <List size={12} strokeWidth={2} /> 大纲
              </button>
              <button
                type="button"
                className="segmented__btn"
                aria-pressed={viewMode === 'card'}
                onClick={() => switchView('card')}
                title="卡片视图：完整信息与重命名"
              >
                <LayoutGrid size={12} strokeWidth={2} /> 卡片
              </button>
            </div>
          )}
          {scenes.length > 1 && viewMode === 'card' && (
            <button
              type="button"
              className="btn btn--outline btn--sm"
              onClick={() => setSortMode(!sortMode)}
              style={{ height: 28, padding: '0 9px', fontSize: 11.5 }}
            >
              {sortMode
                ? <><Check size={12} strokeWidth={2} /> 完成</>
                : <><ArrowUpDown size={12} strokeWidth={2} /> 排序</>}
            </button>
          )}
          {currentProject && !sortMode && (
            <button
              type="button"
              className="btn btn--outline btn--sm"
              onClick={() => addScene(currentProject.id)}
              style={{ height: 28, padding: '0 10px', fontSize: 11.5 }}
            >
              <Plus size={13} strokeWidth={2} /> 添加
            </button>
          )}
        </div>
      </div>

      {/* 分镜列表滚动区（仅此区域滚动，「分镜列表」标题行固定） */}
      <div ref={listRef} className="scene-list">
        {/* Empty state */}
        {scenes.length === 0 ? (
          <div className="scene-empty">
            <LiveOrb
              variant="webgl"
              colors={["#CC785C", "#E8B45A", "#F4E6DE"]}
              size={64}
            />
            <span className="scene-empty__title">还没有分镜</span>
            <span className="scene-empty__body">
              在左侧写一段剧本或画面描述，生成后会在这里逐镜列出。
            </span>
          </div>
        ) : viewMode === 'outline' ? (
          /* 大纲视图：一行一镜，直接拖行重排 */
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
                <SortableOutlineRow
                  key={scene.id}
                  scene={scene}
                  isSelected={selectedSceneId === scene.id}
                  onSelect={() => selectScene(scene.id)}
                />
              ))}
            </SortableContext>
          </DndContext>
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
    </div>
  );
}
