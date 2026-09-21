import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import Tree, { type RawNodeDatum } from 'react-d3-tree';
import {
  Boxes,
  ChevronDown,
  ChevronRight,
  FileAudio,
  FileImage,
  FileVideo,
  Folder,
  FolderOpen,
  MoreHorizontal,
  Package,
  Sparkles,
} from 'lucide-react';
import type { AssetType, LibraryAsset, LibraryFolderNode } from '../../api/library';
import { assetUrl } from '../../config';

export type LibraryTreeNodeKind =
  | 'project'
  | 'system'
  | 'folder'
  | 'asset'
  | 'group'
  | 'more';

export interface LibraryTreeNodeDatum extends RawNodeDatum {
  id: string;
  kind: LibraryTreeNodeKind;
  name: string;
  children?: LibraryTreeNodeDatum[];
  folder?: LibraryFolderNode;
  asset?: LibraryAsset;
  meta?: string;
  hasChildren?: boolean;
  expanded?: boolean;
  loadMoreFor?: string;
}

interface MenuPoint {
  x: number;
  y: number;
  node: LibraryTreeNodeDatum;
}

export interface LibraryTreeProps {
  data: LibraryTreeNodeDatum;
  revision: number;
  selectedIds: string[];
  onOpenNode: (node: LibraryTreeNodeDatum) => void;
  onToggleAsset: (assetId: string) => void;
  onSetSelection: (assetIds: string[]) => void;
  onToggleFolder: (folderId: string) => void;
  onLoadMore: (folderId: string) => void;
  onOpenMenu: (point: MenuPoint) => void;
}

export function LibraryTree({
  data,
  revision,
  selectedIds,
  onOpenNode,
  onToggleAsset,
  onSetSelection,
  onToggleFolder,
  onLoadMore,
  onOpenMenu,
}: LibraryTreeProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const longPressTimer = useRef<number | null>(null);
  const pointerStart = useRef<{ x: number; y: number } | null>(null);
  const suppressClick = useRef(false);
  const [dimensions, setDimensions] = useState({ width: 900, height: 680 });
  const [marquee, setMarquee] = useState<{ x: number; y: number; width: number; height: number } | null>(null);
  const selectedSet = useMemo(() => new Set(selectedIds), [selectedIds]);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const update = () => setDimensions({
      width: Math.max(420, element.clientWidth),
      height: Math.max(420, element.clientHeight),
    });
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onSetSelection([]);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onSetSelection]);

  useEffect(() => () => {
    if (longPressTimer.current != null) window.clearTimeout(longPressTimer.current);
  }, []);

  const clearLongPress = useCallback(() => {
    if (longPressTimer.current != null) {
      window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  const pointInContainer = (event: ReactPointerEvent) => {
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  };

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    const target = event.target as HTMLElement;
    if (target.closest('button,a,input')) return;
    const point = pointInContainer(event);
    pointerStart.current = point;
    clearLongPress();
    longPressTimer.current = window.setTimeout(() => {
      setMarquee({ x: point.x, y: point.y, width: 0, height: 0 });
      longPressTimer.current = null;
    }, 350);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const start = pointerStart.current;
    if (!start) return;
    const point = pointInContainer(event);
    if (longPressTimer.current != null) {
      if (Math.hypot(point.x - start.x, point.y - start.y) > 8) clearLongPress();
      return;
    }
    if (!marquee) return;
    setMarquee({
      x: Math.min(start.x, point.x),
      y: Math.min(start.y, point.y),
      width: Math.abs(point.x - start.x),
      height: Math.abs(point.y - start.y),
    });
  };

  const handlePointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    clearLongPress();
    pointerStart.current = null;
    if (!marquee || marquee.width < 4 || marquee.height < 4) {
      setMarquee(null);
      return;
    }
    const containerRect = containerRef.current?.getBoundingClientRect();
    if (!containerRect) return;
    const left = containerRect.left + marquee.x;
    const right = left + marquee.width;
    const top = containerRect.top + marquee.y;
    const bottom = top + marquee.height;
    const next = Array.from(containerRef.current?.querySelectorAll<HTMLElement>('[data-library-asset-id]') || [])
      .filter((node) => {
        const rect = node.getBoundingClientRect();
        return rect.right >= left && rect.left <= right && rect.bottom >= top && rect.top <= bottom;
      })
      .map((node) => node.dataset.libraryAssetId || '')
      .filter(Boolean);
    onSetSelection([...new Set(next)]);
    suppressClick.current = true;
    window.setTimeout(() => { suppressClick.current = false; }, 0);
    setMarquee(null);
    event.preventDefault();
  };

  const renderNode = ({ nodeDatum }: { nodeDatum: RawNodeDatum }) => {
    const node = nodeDatum as LibraryTreeNodeDatum;
    const selected = Boolean(node.asset && selectedSet.has(node.asset.id));
    const canExpand = node.kind === 'folder' || node.kind === 'system' || node.kind === 'project';
    return (
      <g>
        <foreignObject width={260} height={76} x={-130} y={-38}>
          <div
            className={`library-tree-node is-${node.kind}${selected ? ' is-selected' : ''}`}
            data-library-asset-id={node.asset?.id}
            onClick={(event) => {
              if (suppressClick.current) {
                event.preventDefault();
                event.stopPropagation();
                return;
              }
              if (event.ctrlKey || event.metaKey) {
                event.stopPropagation();
                if (node.asset) onToggleAsset(node.asset.id);
                return;
              }
              if (canExpand) onToggleFolder(node.id);
              onOpenNode(node);
            }}
            onContextMenu={(event) => {
              event.preventDefault();
              event.stopPropagation();
              onOpenMenu({ x: event.clientX, y: event.clientY, node });
            }}
          >
            <span className="library-tree-node__icon">{nodeIcon(node.kind, node.expanded, node.asset)}</span>
            <span className="library-tree-node__body">
              <strong title={node.name}>{node.name}</strong>
              {node.meta && <small>{node.meta}</small>}
            </span>
            {canExpand && (
              <button
                type="button"
                className="library-tree-node__toggle"
                aria-label={node.expanded ? '折叠' : '展开'}
                onClick={(event) => {
                  event.stopPropagation();
                  onToggleFolder(node.id);
                }}
              >
                {node.expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
            )}
            {node.kind === 'more' ? (
              <button
                type="button"
                className="library-tree-node__more"
                onClick={(event) => {
                  event.stopPropagation();
                  if (node.loadMoreFor) onLoadMore(node.loadMoreFor);
                }}
              >
                加载更多
              </button>
            ) : (
              <button
                type="button"
                className="library-tree-node__more"
                aria-label="更多操作"
                title="更多操作"
                onClick={(event) => {
                  event.stopPropagation();
                  const rect = event.currentTarget.getBoundingClientRect();
                  onOpenMenu({ x: rect.right, y: rect.bottom, node });
                }}
              >
                <MoreHorizontal size={15} />
              </button>
            )}
          </div>
        </foreignObject>
      </g>
    );
  };

  return (
    <div
      ref={containerRef}
      className="library-tree-canvas"
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={() => {
        clearLongPress();
        pointerStart.current = null;
        setMarquee(null);
      }}
    >
      <Tree
        key={revision}
        data={data}
        dimensions={dimensions}
        orientation="horizontal"
        pathFunc="step"
        translate={{ x: 132, y: dimensions.height / 2 }}
        nodeSize={{ x: 276, y: 84 }}
        separation={{ siblings: 0.92, nonSiblings: 1.05 }}
        collapsible={false}
        zoomable
        draggable={!marquee}
        hasInteractiveNodes
        zoom={0.9}
        scaleExtent={{ min: 0.35, max: 1.7 }}
        renderCustomNodeElement={renderNode}
        svgClassName="library-tree-svg"
      />
      {marquee && (
        <div
          className="library-tree-marquee"
          style={{
            left: marquee.x,
            top: marquee.y,
            width: marquee.width,
            height: marquee.height,
          }}
        />
      )}
    </div>
  );
}

function nodeIcon(
  kind: LibraryTreeNodeKind,
  expanded?: boolean,
  asset?: LibraryAsset,
): ReactNode {
  if (kind === 'project') return <Package size={17} />;
  if (kind === 'folder' || kind === 'system') return expanded ? <FolderOpen size={17} /> : <Folder size={17} />;
  if (kind === 'group') return <Boxes size={17} />;
  if (kind === 'more') return <MoreHorizontal size={16} />;
  if (asset?.coverUrl) return <AssetThumbnail asset={asset} />;
  return <AssetIcon type={asset?.type || 'IMAGE'} />;
}

export function AssetIcon({ type }: { type: AssetType }) {
  if (type === 'VIDEO') return <FileVideo size={17} />;
  if (type === 'AUDIO') return <FileAudio size={17} />;
  if (type === 'GROUP') return <Boxes size={17} />;
  if (type === 'AI_ASSET') return <Sparkles size={17} />;
  return <FileImage size={17} />;
}

function AssetThumbnail({ asset }: { asset: LibraryAsset }) {
  const [failed, setFailed] = useState(false);
  if (failed || !asset.coverUrl) return <AssetIcon type={asset.type} />;
  if (asset.type === 'VIDEO') {
    return (
      <video
        src={assetUrl(asset.coverUrl)}
        muted
        preload="metadata"
        draggable={false}
        onError={() => setFailed(true)}
      />
    );
  }
  return (
    <img
      src={assetUrl(asset.coverUrl)}
      alt=""
      loading="lazy"
      draggable={false}
      onError={() => setFailed(true)}
    />
  );
}
