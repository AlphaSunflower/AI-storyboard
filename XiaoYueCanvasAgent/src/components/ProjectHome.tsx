import { useState } from 'react';
import { ArrowLeft, FileOutput, FolderOpen, Layers, Pencil, Plus, Trash2 } from 'lucide-react';
import { AssetLibrary } from './AssetLibrary';
import type { CanvasSummary, ProjectDocumentV2 } from '../types';

interface ProjectHomeProps {
  project: ProjectDocumentV2;
  canvases: CanvasSummary[];
  onBack: () => void;
  onCreateCanvas: () => void;
  onOpenCanvas: (canvasId: string) => void;
  onRenameCanvas: (canvasId: string, title: string) => void;
  onDeleteCanvas: (canvasId: string) => void;
  onRenameProject: (title: string) => void;
  onDeleteProject: () => void;
  onExportProject: () => void;
  onNotify?: (message: string) => void;
  canvasModeLocked?: boolean;
}

const relativeTime = (timestamp: number) => {
  const minutes = Math.max(0, Math.round((Date.now() - timestamp) / 60000));
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return new Date(timestamp).toLocaleDateString('zh-CN');
};

export function ProjectHome({ project, canvases, onBack, onCreateCanvas, onOpenCanvas, onRenameCanvas, onDeleteCanvas, onRenameProject, onDeleteProject, onExportProject, onNotify, canvasModeLocked = false }: ProjectHomeProps) {
  const [renamingProject, setRenamingProject] = useState(false);
  const [renamingCanvasId, setRenamingCanvasId] = useState<string | null>(null);
  const [pendingDeleteCanvasId, setPendingDeleteCanvasId] = useState<string | null>(null);
  const [confirmDeleteProject, setConfirmDeleteProject] = useState(false);
  const [tab, setTab] = useState<'canvas' | 'assets'>('canvas');

  return (
    <main className="project-home" style={{ minHeight: '100vh', padding: '40px 48px', boxSizing: 'border-box', maxWidth: 1180, margin: '0 auto' }}>
      <header className="project-home__header" style={{ display: 'flex', alignItems: 'flex-start', gap: 24, marginBottom: 32 }}>
        <button
          onClick={onBack}
          disabled={canvasModeLocked}
          title={canvasModeLocked ? '请先离开画布编辑模式' : '返回主页'}
          style={{ ...iconButton, opacity: canvasModeLocked ? 0.4 : 1, cursor: canvasModeLocked ? 'not-allowed' : 'pointer' }}
          aria-label="返回主页"
        ><ArrowLeft size={18} /></button>
        <div style={{ flex: 1 }}>
          <small style={{ opacity: 0.55, letterSpacing: '0.14em' }}>MORROW / PROJECT</small>
          {renamingProject ? (
            <input autoFocus defaultValue={project.title} style={{ fontSize: 28, fontWeight: 700, background: 'transparent', border: 'none', borderBottom: '1px solid rgba(255,255,255,0.3)', color: 'inherit', width: '100%' }} onBlur={(event) => { onRenameProject(event.target.value.trim() || project.title); setRenamingProject(false); }} onKeyDown={(event) => { if (event.key === 'Enter') (event.target as HTMLInputElement).blur(); }} />
          ) : (
            <h1 style={{ fontSize: 30, margin: '2px 0 6px' }}>{project.title || 'Untitled'}</h1>
          )}
          <p style={{ opacity: 0.55, margin: 0 }}>{canvases.length} 个画布 · 项目、原图与每个派生版本保存在本地</p>
        </div>
        <div className="project-home__actions" style={{ display: 'flex', gap: 8 }}>
          <button disabled={canvasModeLocked} onClick={() => setRenamingProject(true)} style={{ ...ghostButton, opacity: canvasModeLocked ? 0.4 : 1 }} aria-label="重命名项目"><Pencil size={15} /> 重命名</button>
          <button disabled={canvasModeLocked} onClick={onExportProject} style={{ ...ghostButton, opacity: canvasModeLocked ? 0.4 : 1 }}><FileOutput size={15} /> 导出</button>
          {confirmDeleteProject ? (
            <span style={{ display: 'flex', gap: 6 }}>
              <button onClick={onDeleteProject} style={dangerButton}>确认删除</button>
              <button onClick={() => setConfirmDeleteProject(false)} style={ghostButton}>取消</button>
            </span>
          ) : (
            <button disabled={canvasModeLocked} onClick={() => setConfirmDeleteProject(true)} style={{ ...dangerGhostButton, opacity: canvasModeLocked ? 0.4 : 1 }} aria-label="删除项目"><Trash2 size={15} /></button>
          )}
        </div>
      </header>

      <div className="project-home__tabs" style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
        <button onClick={() => setTab('canvas')} style={tab === 'canvas' ? tabActive : tabInactive}>画布</button>
        <button onClick={() => setTab('assets')} style={tab === 'assets' ? tabActive : tabInactive}>项目库</button>
      </div>

      {tab === 'canvas' ? (
        <section>
          <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
            <div><small style={{ opacity: 0.55, letterSpacing: '0.14em' }}>CANVASES</small><h2 style={{ margin: '2px 0 0', fontSize: 20 }}>画布</h2></div>
            <button disabled={canvasModeLocked} onClick={onCreateCanvas} style={{ ...primaryButton, opacity: canvasModeLocked ? 0.4 : 1 }}><Plus size={16} /> 新建画布</button>
          </header>

          {canvases.length ? (
            <div className="project-home__grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 16 }}>
              {canvases.map((canvas) => (
                <article key={canvas.id} style={card}>
                  <button onClick={() => onOpenCanvas(canvas.id)} style={{ ...cardOpen, textAlign: 'left' }}>
                    <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: 96, borderRadius: 8, background: 'rgba(255,255,255,0.05)', marginBottom: 12 }}><Layers size={26} style={{ opacity: 0.5 }} /></span>
                    {renamingCanvasId === canvas.id ? (
                      <input autoFocus defaultValue={canvas.title} style={{ fontSize: 16, fontWeight: 600, background: 'transparent', border: 'none', borderBottom: '1px solid rgba(255,255,255,0.3)', color: 'inherit', width: '100%' }} onClick={(event) => event.stopPropagation()} onBlur={(event) => { onRenameCanvas(canvas.id, event.target.value.trim() || canvas.title); setRenamingCanvasId(null); }} onKeyDown={(event) => { if (event.key === 'Enter') (event.target as HTMLInputElement).blur(); }} />
                    ) : (
                      <strong style={{ display: 'block', fontSize: 16 }}>{canvas.title}</strong>
                    )}
                    <small style={{ opacity: 0.55 }}>{relativeTime(canvas.updatedAt)} · {canvas.nodeCount} 节点 · {canvas.assetCount} 素材</small>
                  </button>
                  <div style={{ display: 'flex', gap: 4, marginTop: 8 }}>
                    <button disabled={canvasModeLocked} style={{ ...miniButton, opacity: canvasModeLocked ? 0.4 : 1 }} aria-label={`重命名 ${canvas.title}`} onClick={() => setRenamingCanvasId(canvas.id)}><Pencil size={13} /></button>
                    {pendingDeleteCanvasId === canvas.id ? (
                      <span style={{ display: 'flex', gap: 4, fontSize: 12 }}>
                        <button style={miniDanger} onClick={() => { onDeleteCanvas(canvas.id); setPendingDeleteCanvasId(null); }}>删除</button>
                        <button style={miniButton} onClick={() => setPendingDeleteCanvasId(null)}>取消</button>
                      </span>
                    ) : (
                      <button disabled={canvasModeLocked} style={{ ...miniButton, opacity: canvasModeLocked ? 0.4 : 1 }} aria-label={`删除 ${canvas.title}`} onClick={() => setPendingDeleteCanvasId(canvas.id)}><Trash2 size={13} /></button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '64px 24px', borderRadius: 14, border: '1px dashed rgba(255,255,255,0.16)' }}>
              <FolderOpen size={30} style={{ opacity: 0.5 }} />
              <strong style={{ display: 'block', marginTop: 12 }}>还没有画布</strong>
              <p style={{ opacity: 0.55, margin: '6px 0 16px' }}>在这个项目里新建第一个画布开始创作。</p>
              <button disabled={canvasModeLocked} onClick={onCreateCanvas} style={{ ...primaryButton, opacity: canvasModeLocked ? 0.4 : 1 }}><Plus size={16} /> 新建第一个画布</button>
            </div>
          )}
        </section>
      ) : (
        <AssetLibrary projectId={project.id} onNotify={onNotify} />
      )}
    </main>
  );
}

const iconButton: React.CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'center', width: 40, height: 40, borderRadius: 10, border: '1px solid rgba(255,255,255,0.14)', background: 'transparent', color: 'inherit', cursor: 'pointer' };
const ghostButton: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 12px', borderRadius: 9, border: '1px solid rgba(255,255,255,0.14)', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 13 };
const dangerButton: React.CSSProperties = { ...ghostButton, background: '#e5484d', borderColor: '#e5484d', color: '#fff' };
const dangerGhostButton: React.CSSProperties = { ...iconButton, color: '#ff8a8e', width: 40, height: 40 };
const primaryButton: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '9px 14px', borderRadius: 9, border: 'none', background: '#d7ff54', color: '#1c1e22', cursor: 'pointer', fontSize: 13, fontWeight: 600 };
const card: React.CSSProperties = { padding: 16, borderRadius: 14, border: '1px solid rgba(255,255,255,0.12)', background: 'rgba(255,255,255,0.03)' };
const cardOpen: React.CSSProperties = { display: 'block', width: '100%', background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', padding: 0 };
const miniButton: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', justifyContent: 'center', padding: 5, borderRadius: 6, border: '1px solid rgba(255,255,255,0.12)', background: 'transparent', color: 'inherit', cursor: 'pointer' };
const miniDanger: React.CSSProperties = { ...miniButton, color: '#ff8a8e', borderColor: 'rgba(255,138,142,0.4)' };
const tabActive: React.CSSProperties = { padding: '8px 16px', borderRadius: 8, border: 'none', background: '#d7ff54', color: '#1c1e22', cursor: 'pointer', fontSize: 13, fontWeight: 600 };
const tabInactive: React.CSSProperties = { padding: '8px 16px', borderRadius: 8, border: '1px solid rgba(255,255,255,0.14)', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 13 };
