import { useEffect, useState } from 'react';
import { FileText, Image as ImageIcon, Images, Layers, Library, MessageSquare, Type, Video, Wand2, X } from 'lucide-react';
import { NODE_PRESETS } from '../data/presets';
import { hydrateAsset, listLibraryAssets } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { PersistedAsset } from '../types';
import { getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';

const icons = { text: Type, image: ImageIcon, video: Video, script: FileText, note: MessageSquare, prompt: Wand2, sequence: Layers };

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
  const [libraryAssets, setLibraryAssets] = useState<PersistedAsset[]>([]);
  useEffect(() => { void listLibraryAssets().then(setLibraryAssets); }, []);
  const insertLibraryAsset = (record: PersistedAsset) => {
    const asset = hydrateAsset(record); upsertAsset(asset);
    const item = { assetId: asset.id, label: asset.name.replace(/\.[^.]+$/, ''), position: { x: 320, y: 220 }, size: asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height) };
    if (asset.kind === 'video') addImportedVideos([item]); else addImportedImages([item]);
    onClose();
  };
  return <aside className="floating-drawer node-library"><div className="drawer-heading"><div><span className="eyebrow">BUILDING BLOCKS</span><h2>节点库</h2></div><button className="icon-button" onClick={onClose} aria-label="关闭节点库"><X size={17} /></button></div><p className="drawer-intro">只保留可以真实保存和恢复的图片与文字节点。</p><button className="node-import-action" onClick={() => { onImport?.(); onClose(); }}><span><Images size={18} /></span><strong>导入真实图片</strong><small>PNG / JPEG / WEBP · 本地处理</small></button><div className="node-preset-list">{NODE_PRESETS.map((preset) => { const Icon = icons[preset.kind]; return <button key={preset.kind} className="node-preset" style={{ '--preset-accent': preset.accent } as React.CSSProperties} onClick={() => { addNode(preset.kind, { x: 280, y: 220 }); onClose(); }}><span className="preset-icon"><Icon size={18} /></span><span><strong>{preset.title}</strong><small>{preset.description}</small></span><span className="preset-plus">+</span></button>; })}</div>{libraryAssets.length > 0 && <section className="library-assets"><header><Library size={14} /><strong>本地素材库</strong><span>{libraryAssets.length}</span></header>{libraryAssets.map((record) => <button key={record.id} onClick={() => insertLibraryAsset(record)}><LibraryThumbnail asset={record} /><span><strong>{record.name}</strong><small>{record.width} × {record.height}</small></span><b>+</b></button>)}</section>}</aside>;
}
