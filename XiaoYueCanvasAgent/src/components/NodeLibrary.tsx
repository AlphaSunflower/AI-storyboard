import { useEffect, useState } from 'react';
import { useReactFlow, useStore } from '@xyflow/react';
import { Clapperboard, FileText, Image as ImageIcon, Images, LayoutGrid, Layers, Library, MessageSquare, Sparkles, Type, Video, Wand2, X } from 'lucide-react';
import { hydrateAsset, listLibraryAssets } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { PersistedAsset } from '../types';
import { getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import { listNodeDefinitions, type CanvasNodeKind, type NodeCategory } from '../canvas/graphContracts';

const icons = {
  text: Type,
  image: ImageIcon,
  video: Video,
  script: FileText,
  note: MessageSquare,
  storyboardPlanner: Clapperboard,
  shot: Clapperboard,
  textToImage: Sparkles,
  imageToImage: Images,
  batchImageRefine: Images,
  textToVideo: Video,
  imageToVideo: Video,
  firstLastFrameToVideo: Video,
  referenceToVideo: Video,
} satisfies Record<CanvasNodeKind, typeof Type>;

function LibraryThumbnail({ asset }: { asset: PersistedAsset }) {
  const [url, setUrl] = useState('');
  useEffect(() => { const next = URL.createObjectURL(asset.thumbnailBlob); setUrl(next); return () => URL.revokeObjectURL(next); }, [asset]);
  return url ? <span className="library-thumbnail-wrap"><img src={url} alt="" />{asset.kind === 'video' && <Video size={12} />}</span> : <span className="library-thumbnail-pending" />;
}

export function NodeLibrary({ onClose, onImport }: { onClose: () => void; onImport?: () => void }) {
  const addNode = useCanvasStore((state) => state.addNode);
  const addImportedImages = useCanvasStore((state) => state.addImportedImages);
  const addImportedVideos = useCanvasStore((state) => state.addImportedVideos);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const { screenToFlowPosition } = useReactFlow();
  const flowWidth = useStore((state) => state.width);
  const flowHeight = useStore((state) => state.height);
  const [libraryAssets, setLibraryAssets] = useState<PersistedAsset[]>([]);
  useEffect(() => { void listLibraryAssets().then(setLibraryAssets); }, []);
  // 把新节点落到「当前视口中心」而非硬编码 (280,220)——跟随用户视线。
  const addPosition = () => screenToFlowPosition({ x: flowWidth / 2, y: flowHeight / 2 });
  const insertLibraryAsset = (record: PersistedAsset) => {
    const asset = hydrateAsset(record); upsertAsset(asset);
    const item = { assetId: asset.id, label: asset.name.replace(/\.[^.]+$/, ''), position: addPosition(), size: asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height) };
    if (asset.kind === 'video') addImportedVideos([item]); else addImportedImages([item]);
    onClose();
  };
  const definitions = listNodeDefinitions();
  const categories = [...new Set(definitions.map((definition) => definition.category))] as NodeCategory[];
  return (
    <aside className="floating-drawer node-library">
      <div className="drawer-heading">
        <div><span className="eyebrow">BUILDING BLOCKS</span><h2>节点库</h2></div>
        <button className="icon-button" onClick={onClose} aria-label="关闭节点库"><X size={17} /></button>
      </div>
      <div className="node-library-body">
        <p className="drawer-intro">按输入、分镜和生产能力组织节点，只有合法连接才能在画布上运行。</p>
        <button className="node-import-action" onClick={() => { onImport?.(); onClose(); }}>
          <span><Images size={18} /></span><strong>导入真实图片</strong><small>PNG / JPEG / WEBP · 本地处理</small>
        </button>
        {categories.map((category) => (
          <section key={category} className="node-library-category">
            <header><strong>{category}</strong><span>{definitions.filter((definition) => definition.category === category).length}</span></header>
            <div className="node-preset-list">
              {definitions.filter((definition) => definition.category === category).map((definition) => {
                const Icon = icons[definition.kind];
                const defaults = definition.createDefaultData();
                return (
                  <button
                    key={definition.kind}
                    className="node-preset"
                    style={{ '--preset-accent': defaults.accent } as React.CSSProperties}
                    onClick={() => { addNode(definition.kind, addPosition()); onClose(); }}
                  >
                    <span className="preset-icon"><Icon size={18} /></span>
                    <span><strong>{definition.title}</strong><small>{definition.actions[0]?.label ?? category}</small></span>
                    <span className="preset-plus">+</span>
                  </button>
                );
              })}
            </div>
          </section>
        ))}
        {libraryAssets.length > 0 && (
          <section className="library-assets">
            <header><Library size={14} /><strong>本地素材库</strong><span>{libraryAssets.length}</span></header>
            {libraryAssets.map((record) => (
              <button key={record.id} onClick={() => insertLibraryAsset(record)}>
                <LibraryThumbnail asset={record} /><span><strong>{record.name}</strong><small>{record.width} × {record.height}</small></span><b>+</b>
              </button>
            ))}
          </section>
        )}
      </div>
    </aside>
  );
}
