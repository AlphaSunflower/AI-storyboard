import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import type { CanvasNode } from '../types';
import { GroupedNodeMenu } from './ui/GroupedNodeMenu';

export type CanvasContextTarget =
  | { type: 'pane'; clientX: number; clientY: number; flowPosition: { x: number; y: number } }
  | { type: 'node'; node: CanvasNode; clientX: number; clientY: number; flowPosition: { x: number; y: number } }
  | { type: 'edge'; edgeId: string; clientX: number; clientY: number; flowPosition: { x: number; y: number } };

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
  return <div className={`canvas-menu-item-wrap ${open ? 'is-open' : ''}`} onPointerEnter={() => submenu && setOpen(true)} onPointerLeave={() => setOpen(false)}>
    <button role="menuitem" className="canvas-menu-item" disabled={disabled} title={title} onClick={() => { if (!disabled && !submenu) onSelect?.(); }}>
      <span>{children}</span>{shortcut && <kbd>{shortcut}</kbd>}{submenu && <ChevronRight size={14} />}
    </button>
    {submenu && open && <div className="canvas-context-submenu" role="menu">{submenu}</div>}
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
  onOpenAssets: () => void;
  onAddText: () => void;
  onAddImage: () => void;
  onAddNode?: (kind: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onCopy: () => void;
  onPaste: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
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
    addEventListener('pointerdown', close, true);
    addEventListener('keydown', closeOnEscape);
    addEventListener('blur', onClose);
    addEventListener('wheel', onClose, { passive: true });
    return () => {
      removeEventListener('pointerdown', close, true);
      removeEventListener('keydown', closeOnEscape);
      removeEventListener('blur', onClose);
      removeEventListener('wheel', onClose);
    };
  }, [onClose]);

  const run = (action: () => void) => () => { action(); onClose(); };
  const addNodeSubmenu = <GroupedNodeMenu onAdd={(kind: string) => { if (kind === 'image') run(props.onAddImage)(); else if (kind === 'text') run(props.onAddText)(); else props.onAddNode?.(kind); }} />;

  return <div ref={menuRef} className={`canvas-context-menu ${flyoutLeft ? 'is-flyout-left' : ''}`} role="menu" aria-label="画布右键菜单" style={position} onContextMenu={(event) => event.preventDefault()}>
    {target.type === 'pane' && <>
      <MenuItem onSelect={run(props.onUpload)}>上传</MenuItem>
      <MenuItem onSelect={run(props.onOpenAssets)}>添加资产</MenuItem>
      <Separator />
      <MenuItem submenu={addNodeSubmenu}>添加节点</MenuItem>
      <MenuItem disabled title="首批仅开放图片与文字节点">添加辅助工具</MenuItem>
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
      <Separator />
      <MenuItem shortcut="Backspace / Del" onSelect={run(props.onDelete)}>删除</MenuItem>
      {target.node.data.kind === 'image' && <>
        <Separator />
        <MenuItem disabled={!props.hasImageAsset} title={props.hasImageAsset ? undefined : '请先上传真实图片'} onSelect={run(props.onCopyImage)}>复制到剪贴板</MenuItem>
      </>}
      <Separator />
      <MenuItem disabled title="未配置反馈服务">反馈问题</MenuItem>
    </>}

  </div>;
}
