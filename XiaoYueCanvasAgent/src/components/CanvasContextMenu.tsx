import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import type { CanvasNode } from '../types';
import { GroupedNodeMenu } from './ui/GroupedNodeMenu';

export type CanvasContextTarget =
  | { type: 'pane'; clientX: number; clientY: number; flowPosition: { x: number; y: number } }
  | { type: 'node'; node: CanvasNode; clientX: number; clientY: number; flowPosition: { x: number; y: number } }
  | { type: 'selection'; count: number; clientX: number; clientY: number; flowPosition: { x: number; y: number } };

interface MenuItemProps {
  children: ReactNode;
  shortcut?: string;
  disabled?: boolean;
  title?: string;
  onSelect?: () => void;
  submenu?: ReactNode;
}

function MenuItem({ children, shortcut, disabled, title, onSelect, submenu }: MenuItemProps) {
  const [open, setOpen] = useState(false);
  const closeTimerRef = useRef<number | null>(null);
  const cancelClose = () => {
    if (closeTimerRef.current === null) return;
    window.clearTimeout(closeTimerRef.current);
    closeTimerRef.current = null;
  };
  const openSubmenu = () => {
    cancelClose();
    if (submenu) setOpen(true);
  };
  const scheduleClose = () => {
    if (!submenu) return;
    cancelClose();
    closeTimerRef.current = window.setTimeout(() => {
      closeTimerRef.current = null;
      setOpen(false);
    }, 140);
  };
  useEffect(() => () => {
    if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
  }, []);
  return <div className={`canvas-menu-item-wrap ${open ? 'is-open' : ''}`} onPointerEnter={openSubmenu} onPointerLeave={scheduleClose}>
    <button role="menuitem" className="canvas-menu-item" disabled={disabled} title={title} onClick={() => { if (!disabled && !submenu) onSelect?.(); }}>
      <span>{children}</span>{shortcut && <kbd>{shortcut}</kbd>}{submenu && <ChevronRight size={14} />}
    </button>
    {submenu && open && <div className="canvas-context-submenu" role="menu" onPointerEnter={openSubmenu} onPointerLeave={scheduleClose}>{submenu}</div>}
  </div>;
}

const Separator = () => <div className="canvas-menu-separator" role="separator" />;

interface CanvasContextMenuProps {
  target: CanvasContextTarget;
  canPaste: boolean;
  canUndo: boolean;
  canRedo: boolean;
  onClose: () => void;
  onUpload: () => void;
  /** 从项目库多选素材上传到画布 */
  onOpenAssets: () => void;
  onAddText: () => void;
  onAddImage: () => void;
  onAddNode?: (kind: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onCopy: () => void;
  onCut: () => void;
  onLayout: () => void;
  onPaste: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
  /** 把当前多选节点合并成一个分组（frame） */
  onCreateGroup?: () => void;
  /** 选中某个分组内的全部节点 */
  onSelectGroup?: (groupId: string) => void;
  /** 解散某个分组（节点保留） */
  onUngroupGroup?: (groupId: string) => void;
  /** 执行某个分组（分组成员 + 上游依赖） */
  onRunGroup?: (groupId: string) => void;
  onSaveToLibrary: () => void;
  onFlattenHistory: () => void;
  onDownload: () => void;
  onCopyImage: () => void;
  canFlattenHistory: boolean;
  hasImageAsset: boolean;
  hasMediaAsset: boolean;
}

export function CanvasContextMenu(props: CanvasContextMenuProps) {
  const { target, onClose } = props;
  const menuRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: target.clientX, top: target.clientY });
  const [flyoutLeft, setFlyoutLeft] = useState(false);

  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!menu) return;
    setPosition({
      left: Math.max(8, Math.min(target.clientX, window.innerWidth - menu.offsetWidth - 8)),
      top: Math.max(8, Math.min(target.clientY, window.innerHeight - menu.offsetHeight - 8)),
    });
    setFlyoutLeft(target.clientX + menu.offsetWidth + 202 > window.innerWidth);
  }, [target]);

  useEffect(() => {
    const close = (event: PointerEvent) => { if (!menuRef.current?.contains(event.target as Node)) onClose(); };
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    const closeOnWheel = (event: WheelEvent) => { if (!menuRef.current?.contains(event.target as Node)) onClose(); };
    addEventListener('pointerdown', close, true);
    addEventListener('keydown', closeOnEscape);
    addEventListener('blur', onClose);
    addEventListener('wheel', closeOnWheel, { passive: true });
    return () => {
      removeEventListener('pointerdown', close, true);
      removeEventListener('keydown', closeOnEscape);
      removeEventListener('blur', onClose);
      removeEventListener('wheel', closeOnWheel);
    };
  }, [onClose]);

  const run = (action: () => void) => () => { action(); onClose(); };
  const addNodeSubmenu = <GroupedNodeMenu onAdd={(kind: string) => {
    if (kind === 'image') run(props.onAddImage)();
    else if (kind === 'text') run(props.onAddText)();
    else if (props.onAddNode) run(() => props.onAddNode!(kind))();
  }} />;

  return <div ref={menuRef} className={`canvas-context-menu ${flyoutLeft ? 'is-flyout-left' : ''}`} role="menu" aria-label="画布右键菜单" style={position} onContextMenu={(event) => event.preventDefault()}>
    {target.type === 'pane' && <>
      <MenuItem onSelect={run(props.onUpload)} title="图片、视频文件按类型落成对应节点">上传本地文件</MenuItem>
      <MenuItem onSelect={run(props.onOpenAssets)} title="从项目库多选素材上传到画布">上传资产</MenuItem>
      <Separator />
      <MenuItem submenu={addNodeSubmenu}>添加节点</MenuItem>
      <MenuItem disabled title="首批仅开放图片与文字节点">添加辅助工具</MenuItem>
      <Separator />
      <MenuItem onSelect={run(props.onLayout)}>整理编排</MenuItem>
      <Separator />
      <MenuItem disabled={!props.canUndo} shortcut="Ctrl Z" onSelect={run(props.onUndo)}>撤销</MenuItem>
      <MenuItem disabled={!props.canRedo} shortcut="Shift Ctrl Z" onSelect={run(props.onRedo)}>重做</MenuItem>
      <Separator />
      <MenuItem disabled={!props.canPaste} shortcut="Ctrl V" onSelect={run(props.onPaste)}>粘贴</MenuItem>
    </>}

    {target.type === 'node' && <>
      {(target.node.data.kind === 'image' || target.node.data.kind === 'video') && <>
        <MenuItem disabled={!props.hasMediaAsset} title={props.hasMediaAsset ? undefined : '请先上传真实素材'} onSelect={run(props.onSaveToLibrary)}>保存到素材库</MenuItem>
        <Separator />
        {target.node.data.kind === 'image' && <MenuItem disabled={!props.canFlattenHistory} title={props.canFlattenHistory ? undefined : '当前节点没有需要合并的派生历史'} onSelect={run(props.onFlattenHistory)}>应用所有历史</MenuItem>}
        <MenuItem disabled={!props.hasMediaAsset} title={props.hasMediaAsset ? undefined : '请先上传真实素材'} onSelect={run(props.onDownload)}>下载</MenuItem>
        <Separator />
      </>}
      <MenuItem shortcut="Ctrl C" onSelect={run(props.onCopy)}>复制</MenuItem>
      <MenuItem disabled={!props.canPaste} shortcut="Ctrl V" onSelect={run(props.onPaste)}>粘贴</MenuItem>
      <MenuItem onSelect={run(props.onDuplicate)}>副本</MenuItem>
      {target.node.data.groupId && (props.onSelectGroup || props.onUngroupGroup || props.onRunGroup) && <>
        <Separator />
        {props.onRunGroup && (
          <MenuItem onSelect={run(() => props.onRunGroup!(String(target.node.data.groupId)))}>执行分组</MenuItem>
        )}
        {props.onSelectGroup && (
          <MenuItem onSelect={run(() => props.onSelectGroup!(String(target.node.data.groupId)))}>选中所在分组</MenuItem>
        )}
        {props.onUngroupGroup && (
          <MenuItem onSelect={run(() => props.onUngroupGroup!(String(target.node.data.groupId)))}>解散分组</MenuItem>
        )}
      </>}
      <Separator />
      <MenuItem onSelect={run(props.onLayout)}>整理编排</MenuItem>
      <Separator />
      <MenuItem shortcut="Ctrl D / Del" onSelect={run(props.onDelete)}>删除</MenuItem>
      {target.node.data.kind === 'image' && <>
        <Separator />
        <MenuItem disabled={!props.hasImageAsset} title={props.hasImageAsset ? undefined : '请先上传真实图片'} onSelect={run(props.onCopyImage)}>复制到剪贴板</MenuItem>
      </>}
      <Separator />
      <MenuItem disabled title="未配置反馈服务">反馈问题</MenuItem>
    </>}

    {target.type === 'selection' && <>
      <MenuItem shortcut="Ctrl C" onSelect={run(props.onCopy)}>复制</MenuItem>
      <MenuItem shortcut="Ctrl X" onSelect={run(props.onCut)}>剪切</MenuItem>
      <Separator />
      {props.onCreateGroup && <MenuItem shortcut="Ctrl G" onSelect={run(props.onCreateGroup)}>创建分组</MenuItem>}
      {props.onCreateGroup && <Separator />}
      <MenuItem onSelect={run(props.onLayout)}>整理编排</MenuItem>
      <Separator />
      <MenuItem shortcut="Ctrl D / Del" onSelect={run(props.onDelete)}>删除</MenuItem>
    </>}

  </div>;
}
