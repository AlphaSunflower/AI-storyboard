import { useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { createPortal } from 'react-dom';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { ViewportPortal, useReactFlow } from '@xyflow/react';
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ChevronRight,
  Image as ImageIcon,
  LoaderCircle,
  MoreHorizontal,
  Pencil,
  Play,
  Trash2,
  Video,
} from 'lucide-react';
import {
  buildGroupLayout,
  collectCanvasGroups,
  groupDescendantIds,
  groupExecutionProgress,
  groupMemberNodes,
  groupResultAssetIds,
  COLLAPSED_FRAME_HEIGHT,
  COLLAPSED_FRAME_WIDTH,
  type CanvasGroup,
} from '../canvas/groups';
import { getAsset } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { ImagePreviewOverlay, type ImagePreviewTarget } from './ImagePreviewOverlay';
import type { CanvasNode } from '../types';

interface DragState {
  pointerId: number;
  start: { x: number; y: number };
  positions: Map<string, { x: number; y: number }>;
  moved: boolean;
}

/** 标题条里的组内结果缩略图：能预览图片，视频只显示类型图标。 */
function GroupResultThumb({ assetId, onPreview }: { assetId: string; onPreview: (target: ImagePreviewTarget) => void }) {
  const asset = useMediaStore((state) => state.assets[assetId]);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const blob = asset?.thumbnailBlob ?? asset?.previewBlob;
  const url = useObjectUrl(blob);

  useEffect(() => {
    if (asset) return;
    void getAsset(assetId).then((loaded) => {
      if (loaded) upsertAsset(loaded);
    }).catch(() => undefined);
  }, [asset, assetId, upsertAsset]);

  const label = asset?.name ?? assetId.slice(0, 8);
  const kind = asset?.kind ?? 'image';
  if (kind === 'image' && url) {
    return (
      <button
        type="button"
        className="canvas-frame__result nodrag nopan"
        aria-label={`预览组内结果 ${label}`}
        title={label}
        onPointerDown={(event) => event.stopPropagation()}
        onClick={(event) => {
          event.stopPropagation();
          onPreview({ assetId, label });
        }}
      >
        <img src={url} alt={label} loading="lazy" decoding="async" />
      </button>
    );
  }
  return (
    <span className="canvas-frame__result is-placeholder" title={label} aria-hidden="true">
      {kind === 'video' ? <Video size={11} /> : <ImageIcon size={11} />}
    </span>
  );
}

/**
 * 画布分组框（frame）。
 *
 * <p>挂在 React Flow 的 `ViewportPortal` 里，与节点共用同一套世界坐标：缩放/平移不用自己算。
 * 框只做组织与批量拖动，不参与连线、不进入执行计划；成员关系存在 node.data.groupId，
 * 因此撤销、增量补丁、项目包导入导出都自动覆盖。
 *
 * <p>层级：z-index:-1，画在节点/连线下面；只有标题条接指针事件，框内空白不挡画布操作。
 * <p>尺寸用画布实际渲染的节点（compact 模式会被收窄），不是 store 里的原始宽高，
 * 否则收起节点后框会大出一圈。
 * <p>嵌套分组：父框把子框并入包围盒；折叠的父分组会连同子分组一起隐藏，只留自己的折叠条。
 */
export function CanvasFrameLayer({ nodes, onNotify, onRunGroup }: {
  nodes: CanvasNode[];
  onNotify?: (message: string) => void;
  /** 执行分组：由宿主负责取成员、调执行计划并给出错误提示。 */
  onRunGroup?: (groupId: string) => void;
}) {
  const beginNodeDrag = useCanvasStore((state) => state.beginNodeDrag);
  const endNodeDrag = useCanvasStore((state) => state.endNodeDrag);
  const setNodePositions = useCanvasStore((state) => state.setNodePositions);
  const selectNodes = useCanvasStore((state) => state.selectNodes);
  const ungroupNodes = useCanvasStore((state) => state.ungroupNodes);
  const renameGroup = useCanvasStore((state) => state.renameGroup);
  const toggleGroupCollapsed = useCanvasStore((state) => state.toggleGroupCollapsed);
  const nestGroup = useCanvasStore((state) => state.nestGroup);
  const deleteNodes = useCanvasStore((state) => state.deleteNodes);
  const generationRecords = useCanvasStore((state) => state.generationRecords);
  const { screenToFlowPosition } = useReactFlow();
  const dragRef = useRef<DragState | null>(null);
  const renameClosedRef = useRef(false);
  const [editing, setEditing] = useState<{ id: string; draft: string } | null>(null);
  const [previewTarget, setPreviewTarget] = useState<ImagePreviewTarget | null>(null);
  const [frameMenu, setFrameMenu] = useState<{ group: CanvasGroup; x: number; y: number } | null>(null);

  useEffect(() => {
    if (!frameMenu) return;
    const close = (event: PointerEvent) => {
      const target = event.target;
      if (target instanceof Element && target.closest('.canvas-frame-context-menu')) return;
      setFrameMenu(null);
    };
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') setFrameMenu(null); };
    const onBlur = () => setFrameMenu(null);
    addEventListener('pointerdown', close, true);
    addEventListener('keydown', onKeyDown);
    addEventListener('blur', onBlur);
    return () => {
      removeEventListener('pointerdown', close, true);
      removeEventListener('keydown', onKeyDown);
      removeEventListener('blur', onBlur);
    };
  }, [frameMenu]);

  const groups = useMemo(() => collectCanvasGroups(nodes), [nodes]);
  const layout = useMemo(() => buildGroupLayout(groups), [groups]);

  const startDrag = (event: ReactPointerEvent<HTMLElement>, group: CanvasGroup) => {
    if (event.button !== 0) return;
    if ((event.target as HTMLElement).closest('button, input')) return;
    event.preventDefault();
    event.stopPropagation();
    // 递归成员：嵌套子分组的节点也跟着父分组走。
    const members = groupMemberNodes(groups, group.id);
    // 锁定节点（draggable=false，Agent 的 canvas.node.pin）不跟着整组走。
    const movable = members.filter((node) => node.draggable !== false);
    if (!movable.length) {
      selectNodes(members.map((node) => node.id));
      return;
    }
    const start = screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const positions = new Map(movable.map((node) => [node.id, { ...node.position }]));
    dragRef.current = { pointerId: event.pointerId, start, positions, moved: false };
    beginNodeDrag();

    const onMove = (moveEvent: PointerEvent) => {
      const drag = dragRef.current;
      if (!drag || moveEvent.pointerId !== drag.pointerId) return;
      const current = screenToFlowPosition({ x: moveEvent.clientX, y: moveEvent.clientY });
      const dx = current.x - drag.start.x;
      const dy = current.y - drag.start.y;
      if (!drag.moved) {
        // 2px 死区：手抖不算拖动，松手仍按“点标题选中整组”处理。
        if (Math.hypot(dx, dy) < 2) return;
        drag.moved = true;
      }
      const next: Record<string, { x: number; y: number }> = {};
      drag.positions.forEach((position, id) => {
        next[id] = { x: position.x + dx, y: position.y + dy };
      });
      setNodePositions(next);
    };

    const finish = (upEvent?: PointerEvent) => {
      if (upEvent && dragRef.current && upEvent.pointerId !== dragRef.current.pointerId) return;
      removeEventListener('pointermove', onMove);
      removeEventListener('pointerup', finish);
      removeEventListener('pointercancel', finish);
      const drag = dragRef.current;
      dragRef.current = null;
      if (!drag) return;
      endNodeDrag();
      if (!drag.moved) {
        // 折叠态的标题条单击 = 展开，而不是选中一组看不见的节点。
        if (group.collapsed) toggleGroupCollapsed(group.id, false);
        else selectNodes(members.map((node) => node.id));
      }
    };

    addEventListener('pointermove', onMove);
    addEventListener('pointerup', finish);
    addEventListener('pointercancel', finish);
  };

  const startRename = (group: CanvasGroup) => {
    renameClosedRef.current = false;
    setEditing({ id: group.id, draft: group.title });
  };

  const beginRename = (event: ReactMouseEvent, group: CanvasGroup) => {
    event.preventDefault();
    event.stopPropagation();
    startRename(group);
  };

  const finishRename = (save: boolean) => {
    if (renameClosedRef.current) return;
    renameClosedRef.current = true;
    const current = editing;
    setEditing(null);
    if (save && current) renameGroup(current.id, current.draft);
  };

  const moveGroup = (group: CanvasGroup, parentId: string | null) => {
    if (!nestGroup(group.id, parentId)) {
      onNotify?.('不能移动到该分组：目标不存在或会形成循环嵌套');
      return;
    }
    const parent = parentId ? groups.find((candidate) => candidate.id === parentId) : undefined;
    onNotify?.(parent ? `已将「${group.title}」移入「${parent.title}」` : `已将「${group.title}」移出父分组`);
  };

  const openFrameMenu = (event: ReactMouseEvent, group: CanvasGroup) => {
    event.preventDefault();
    event.stopPropagation();
    setFrameMenu({ group, x: event.clientX, y: event.clientY });
  };

  const deleteGroup = (group: CanvasGroup) => {
    const ids = groupMemberNodes(groups, group.id).map((node) => node.id);
    if (!ids.length) return;
    deleteNodes(ids);
    onNotify?.(`已删除分组「${group.title}」及其 ${ids.length} 个节点，可用撤销恢复`);
  };

  if (!layout.length) return null;

  return (
    <>
      <ViewportPortal>
        {layout.map(({ group, bounds, depth, hiddenByCollapsedAncestor }) => {
          if (hiddenByCollapsedAncestor) return null;
          const collapsed = group.collapsed;
          const members = groupMemberNodes(groups, group.id);
          const progress = groupExecutionProgress(members);
          const results = groupResultAssetIds(members, generationRecords);
          const parent = group.parentId ? groups.find((candidate) => candidate.id === group.parentId) : undefined;
          const descendants = groupDescendantIds(groups, group.id);
          const candidates = groups.filter((candidate) =>
            candidate.id !== group.id && candidate.id !== group.parentId && !descendants.has(candidate.id));
          const resultLimit = collapsed || bounds.width < 480 ? 2 : 4;
          const selected = members.some((node) => node.selected);
          return (
            <section
              key={group.id}
              className={`canvas-frame${selected ? ' is-selected' : ''}${collapsed ? ' is-collapsed' : ''}`}
              style={{
                left: bounds.x,
                top: bounds.y,
                width: collapsed ? COLLAPSED_FRAME_WIDTH : bounds.width,
                height: collapsed ? COLLAPSED_FRAME_HEIGHT : bounds.height,
              }}
              data-group-id={group.id}
              data-group-depth={depth}
              aria-label={`分组 ${group.title}${parent ? `（在 ${parent.title} 内）` : ''}`}
              role="group"
            >
              <header
                className="canvas-frame__head"
                tabIndex={0}
                aria-expanded={!collapsed}
                onPointerDown={(event) => startDrag(event, group)}
                onDoubleClick={collapsed ? undefined : (event) => beginRename(event, group)}
                onContextMenu={(event) => openFrameMenu(event, group)}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  if ((event.target as HTMLElement).closest('button, input')) return;
                  event.preventDefault();
                  event.stopPropagation();
                  if (collapsed) toggleGroupCollapsed(group.id, false);
                  else selectNodes(members.map((node) => node.id));
                }}
                title={collapsed
                  ? '单击展开；拖动整组；右键节点可执行/解散'
                  : '拖动整组；单击选中整组；双击重命名；拖到另一个分组可实现嵌套'}
              >
                <button
                  type="button"
                  className="canvas-frame__collapse nodrag nopan"
                  aria-label={collapsed ? `展开分组 ${group.title}` : `折叠分组 ${group.title}`}
                  title={collapsed ? '展开分组' : '折叠分组'}
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={(event) => {
                    event.stopPropagation();
                    toggleGroupCollapsed(group.id, !collapsed);
                  }}
                >
                  {collapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                </button>
                <span className="canvas-frame__dot" aria-hidden="true" />
                {editing?.id === group.id ? (
                  <input
                    className="canvas-frame__rename nodrag nopan"
                    autoFocus
                    aria-label="分组名称"
                    value={editing.draft}
                    onChange={(event) => setEditing({ id: group.id, draft: event.target.value })}
                    onPointerDown={(event) => event.stopPropagation()}
                    onKeyDown={(event) => {
                      event.stopPropagation();
                      if (event.key === 'Enter') { event.preventDefault(); finishRename(true); }
                      if (event.key === 'Escape') { event.preventDefault(); finishRename(false); }
                    }}
                    onBlur={() => finishRename(true)}
                  />
                ) : (
                  <strong className="canvas-frame__title" title={parent ? `${parent.title} / ${group.title}` : group.title}>
                    {group.title}
                  </strong>
                )}
                {progress.active > 0 && (
                  <span className="canvas-frame__progress is-running" role="status" title={`生成中 ${progress.active}/${progress.total}`}>
                    <LoaderCircle size={10} className="is-spinning" />
                    {collapsed ? `${progress.active}/${progress.total}` : `生成中 ${progress.active}/${progress.total}`}
                  </span>
                )}
                {progress.active === 0 && progress.failed > 0 && (
                  <span className="canvas-frame__progress is-failed" role="status" title={`失败 ${progress.failed}/${progress.total}`}>
                    <AlertTriangle size={10} />
                    {collapsed ? `${progress.failed}/${progress.total}` : `失败 ${progress.failed}/${progress.total}`}
                  </span>
                )}
                {progress.active === 0 && progress.failed === 0 && progress.total > 0 && progress.done === progress.total && (
                  <span className="canvas-frame__progress is-done" role="status" title={`已完成 ${progress.done}/${progress.total}`}>
                    <Check size={10} />
                    {collapsed ? `${progress.done}/${progress.total}` : `已完成 ${progress.done}/${progress.total}`}
                  </span>
                )}
                {results.length > 0 && (
                  <span className="canvas-frame__results" aria-label={`组内结果 ${results.length}`}>
                    {results.slice(0, resultLimit).map((assetId) => (
                      <GroupResultThumb key={assetId} assetId={assetId} onPreview={setPreviewTarget} />
                    ))}
                    {results.length > resultLimit && (
                      <span className="canvas-frame__result-more" title={`还有 ${results.length - resultLimit} 个结果`}>
                        +{results.length - resultLimit}
                      </span>
                    )}
                  </span>
                )}
                <small className="canvas-frame__count">{members.length} 个节点</small>
                {onRunGroup && (
                  <button
                    type="button"
                    className="canvas-frame__run nodrag nopan"
                    aria-label={`执行分组 ${group.title}`}
                    title="执行分组（自动带上依赖节点）"
                    onPointerDown={(event) => event.stopPropagation()}
                    onClick={(event) => {
                      event.stopPropagation();
                      onRunGroup(group.id);
                    }}
                  >
                    <Play size={10} fill="currentColor" />
                    {!collapsed && <span className="canvas-frame__run-text">执行</span>}
                  </button>
                )}
                <span className="canvas-frame__actions">
                  <DropdownMenu.Root>
                    <DropdownMenu.Trigger asChild>
                      <button
                        type="button"
                        className="canvas-frame__action nodrag nopan"
                        aria-label={`分组操作 ${group.title}`}
                        title="分组操作"
                        onPointerDown={(event) => event.stopPropagation()}
                      >
                        <MoreHorizontal size={15} />
                      </button>
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Portal>
                      <DropdownMenu.Content
                        className="canvas-item-menu canvas-frame-menu"
                        align="end"
                        sideOffset={6}
                        aria-label={`分组操作 ${group.title}`}
                      >
                        {candidates.length > 0 && (
                          <>
                            <DropdownMenu.Label className="canvas-frame-menu__label">移动到分组</DropdownMenu.Label>
                            {candidates.map((candidate) => (
                              <DropdownMenu.Item
                                key={candidate.id}
                                className="canvas-frame-menu__item"
                                onSelect={() => moveGroup(group, candidate.id)}
                              >
                                移入「{candidate.title}」
                              </DropdownMenu.Item>
                            ))}
                          </>
                        )}
                        {parent && (
                          <DropdownMenu.Item className="canvas-frame-menu__item" onSelect={() => moveGroup(group, null)}>
                            移出「{parent.title}」
                          </DropdownMenu.Item>
                        )}
                        {(candidates.length > 0 || parent) && <DropdownMenu.Separator className="project-menu-divider" />}
                        <DropdownMenu.Item className="canvas-frame-menu__item" onSelect={() => startRename(group)}>
                          重命名
                        </DropdownMenu.Item>
                        <DropdownMenu.Item
                          className="canvas-frame-menu__item"
                          onSelect={() => toggleGroupCollapsed(group.id, !collapsed)}
                        >
                          {collapsed ? '展开' : '折叠'}
                        </DropdownMenu.Item>
                        <DropdownMenu.Separator className="project-menu-divider" />
                        <DropdownMenu.Item
                          className="canvas-frame-menu__item is-danger"
                          onSelect={() => {
                            ungroupNodes(group.id);
                            onNotify?.('已解散分组，节点保留在画布上');
                          }}
                        >
                          解散分组
                        </DropdownMenu.Item>
                      </DropdownMenu.Content>
                    </DropdownMenu.Portal>
                  </DropdownMenu.Root>
                </span>
              </header>
            </section>
          );
        })}
      </ViewportPortal>
      {frameMenu && createPortal(
        <div
          className="canvas-frame-context-menu"
          role="menu"
          aria-label={`分组菜单 ${frameMenu.group.title}`}
          style={{
            left: Math.min(frameMenu.x, Math.max(8, window.innerWidth - 200)),
            top: Math.min(frameMenu.y, Math.max(8, window.innerHeight - 280)),
          }}
          onPointerDown={(event) => event.stopPropagation()}
          onContextMenu={(event) => event.preventDefault()}
        >
          <div className="canvas-frame-context-menu__title">{frameMenu.group.title}</div>
          {onRunGroup && (
            <button type="button" role="menuitem" onClick={() => { const group = frameMenu.group; setFrameMenu(null); onRunGroup(group.id); }}>
              <Play size={13} />执行
            </button>
          )}
          <button type="button" role="menuitem" onClick={() => { const group = frameMenu.group; setFrameMenu(null); startRename(group); }}>
            <Pencil size={13} />重命名
          </button>
          <button type="button" role="menuitem" onClick={() => {
            const group = frameMenu.group;
            setFrameMenu(null);
            toggleGroupCollapsed(group.id, !group.collapsed);
          }}>
            {frameMenu.group.collapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
            {frameMenu.group.collapsed ? '展开' : '折叠'}
          </button>
          <div className="canvas-frame-context-menu__sep" />
          <button type="button" role="menuitem" onClick={() => {
            const group = frameMenu.group;
            setFrameMenu(null);
            ungroupNodes(group.id);
            onNotify?.('已解除分组，节点保留在画布上');
          }}>
            解除分组
          </button>
          <button type="button" role="menuitem" className="is-danger" onClick={() => {
            const group = frameMenu.group;
            setFrameMenu(null);
            deleteGroup(group);
          }}>
            <Trash2 size={13} />删除分组（含 {groupMemberNodes(groups, frameMenu.group.id).length} 个节点）
          </button>
        </div>,
        document.body,
      )}
      {previewTarget && <ImagePreviewOverlay target={previewTarget} onClose={() => setPreviewTarget(null)} />}
    </>
  );
}
