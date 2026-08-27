import { useRef, useState } from 'react';
import { ArrowRight, FileArchive, FolderOpen, Image, Plus, Trash2 } from 'lucide-react';
import type { ProjectSummary } from '../types';

interface HomePageProps {
  projects: ProjectSummary[];
  loading: boolean;
  onCreate: () => void;
  onOpen: (projectId: string) => void;
  onDelete: (projectId: string) => void;
  onImport: (file: File) => void;
}

const relativeTime = (timestamp: number) => {
  const minutes = Math.max(0, Math.round((Date.now() - timestamp) / 60000));
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return new Date(timestamp).toLocaleDateString('zh-CN');
};

export function HomePage({ projects, loading, onCreate, onOpen, onDelete, onImport }: HomePageProps) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  return (
    <main className="local-home">
      <input ref={fileInput} className="canvas-file-input" type="file" accept=".morrow,application/x-morrow-project,application/zip" onChange={(event) => { const file = event.target.files?.[0]; if (file) onImport(file); event.target.value = ''; }} />
      <header className="local-home-header">
        <div><small>MORROW / LOCAL CREATIVE WORKBENCH</small><h1>设计发生在你的设备上。</h1><p>项目、原图与每个派生版本保存在本地。没有演示素材，没有伪造任务，也不会把文件上传到未知服务。</p></div>
        <div className="local-home-actions"><button className="local-primary" onClick={onCreate}><Plus size={17} /> 新建项目</button><button onClick={() => fileInput.current?.click()}><FileArchive size={17} /> 导入 .morrow</button></div>
      </header>


      <section className="local-projects">
        <header><div><small>LOCAL PROJECTS</small><h2>最近项目</h2></div><span>{projects.length} 个项目</span></header>
        {loading ? <div className="local-empty">正在读取本地项目…</div> : projects.length ? (
          <div className="local-project-grid">{projects.map((project) => (
            <article className="local-project-card" key={project.id}>
              <button className="local-project-open" onClick={() => onOpen(project.id)}>
                <span className="local-project-art"><Image size={25} /><i>{project.assetCount}</i></span>
                <span className="local-project-copy"><strong>{project.title || 'Untitled'}</strong><small>{relativeTime(project.updatedAt)} · {project.nodeCount} 个节点 · {project.assetCount} 项素材</small></span>
                <ArrowRight size={17} />
              </button>
              {pendingDeleteId === project.id ? <span className="local-delete-confirm"><button onClick={() => { onDelete(project.id); setPendingDeleteId(null); }}>确认删除</button><button onClick={() => setPendingDeleteId(null)}>取消</button></span> : <button className="local-project-delete" aria-label={`删除 ${project.title}`} title="删除本地项目" onClick={() => setPendingDeleteId(project.id)}><Trash2 size={15} /></button>}
            </article>
          ))}</div>
        ) : (
          <div className="local-empty"><FolderOpen size={30} /><strong>还没有本地项目</strong><p>新建一个空画布，或导入包含真实素材的 .morrow 项目包。</p><button onClick={onCreate}><Plus size={16} /> 新建第一个项目</button></div>
        )}
      </section>
    </main>
  );
}
