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

const eyebrow = 'font-mono text-[11px] tracking-[0.2em] text-acid';

// 本地项目中心：深墨绿 + 荧光绿工作台质感，与登录页/画布同一套主题。
export function HomePage({ projects, loading, onCreate, onOpen, onDelete, onImport }: HomePageProps) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  return (
    <main className="min-h-dvh overflow-auto px-6 py-10 md:px-14"
      style={{ background: 'radial-gradient(circle at 82% 8%, rgba(215,255,84,0.10), transparent 30%), radial-gradient(circle at 16% 90%, rgba(28,50,42,0.9), transparent 40%), var(--canvas)' }}>
      <input ref={fileInput} className="canvas-file-input" type="file" accept=".morrow,application/x-morrow-project,application/zip" onChange={(event) => { const file = event.target.files?.[0]; if (file) onImport(file); event.target.value = ''; }} />

      <header className="mx-auto flex max-w-6xl flex-wrap items-end justify-between gap-8 border-b border-[var(--border-dark)] pb-8">
        <div>
          <small className={eyebrow}>MORROW / LOCAL CREATIVE WORKBENCH</small>
          <h1 className="mt-4 max-w-xl text-4xl font-bold tracking-tight text-foreground md:text-5xl leading-[1.05]">设计发生在你的设备上<span className="text-acid">。</span></h1>
          <p className="mt-4 max-w-xl text-sm leading-relaxed text-muted-foreground">项目、原图与每个派生版本保存在本地。没有演示素材，没有伪造任务，也不会把文件上传到未知服务。</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <button className="flex h-11 items-center gap-2 rounded-xl bg-acid px-5 font-semibold text-ink transition hover:brightness-105" onClick={onCreate}><Plus size={17} /> 新建项目</button>
          <button className="flex h-11 items-center gap-2 rounded-xl border border-[var(--border-dark)] px-5 text-foreground transition hover:border-acid hover:text-acid" onClick={() => fileInput.current?.click()}><FileArchive size={17} /> 导入 .morrow</button>
        </div>
      </header>

      <section className="mx-auto max-w-6xl py-10">
        <header className="flex items-end justify-between mb-4">
          <div><small className={eyebrow}>LOCAL PROJECTS</small><h2 className="mt-2 text-2xl font-semibold tracking-tight text-foreground">最近项目</h2></div>
          <span className="font-mono text-xs text-muted-foreground">{projects.length} 个项目</span>
        </header>
        {loading ? <div className="grid place-items-center rounded-2xl border border-dashed border-[var(--border-dark)] py-16 text-muted-foreground">正在读取本地项目…</div> : projects.length ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">{projects.map((project) => (
            <article key={project.id} className="group flex items-center gap-4 rounded-2xl border border-[var(--border-dark)] bg-card p-4 transition hover:border-[var(--acid)] hover:shadow-[0_0_24px_rgba(215,255,84,0.08)]">
              <button className="flex flex-1 items-center gap-4 rounded-xl text-left" onClick={() => onOpen(project.id)}>
                <span className="relative grid h-14 w-14 shrink-0 place-items-center rounded-2xl bg-[rgba(215,255,84,0.12)] text-acid"><Image size={24} /><i className="absolute bottom-1 right-1.5 font-mono text-[9px] not-italic text-acid/80">{project.assetCount}</i></span>
                <span className="min-w-0">
                  <strong className="block truncate text-[15px] font-semibold text-foreground">{project.title || 'Untitled'}</strong>
                  <small className="mt-1 block truncate text-xs text-muted-foreground">{relativeTime(project.updatedAt)} · {project.nodeCount} 个节点 · {project.assetCount} 项素材</small>
                </span>
                <ArrowRight size={17} className="ml-2 shrink-0 text-muted-foreground transition group-hover:translate-x-1 group-hover:text-acid" />
              </button>
              {pendingDeleteId === project.id ? <span className="flex gap-1.5"><button className="rounded-md bg-destructive px-2.5 py-1.5 text-xs font-medium text-destructive-foreground" onClick={() => { onDelete(project.id); setPendingDeleteId(null); }}>确认删除</button><button className="rounded-md border border-[var(--border-dark)] px-2.5 py-1.5 text-xs text-muted-foreground" onClick={() => setPendingDeleteId(null)}>取消</button></span> : <button className="shrink-0 rounded-lg p-2 text-muted-foreground transition hover:bg-[rgba(255,107,94,0.12)] hover:text-destructive" aria-label={`删除 ${project.title}`} title="删除本地项目" onClick={() => setPendingDeleteId(project.id)}><Trash2 size={15} /></button>}
            </article>
          ))}</div>
        ) : (
          <div className="grid place-items-center gap-3 rounded-2xl border border-dashed border-[var(--border-dark)] py-20 text-center">
            <FolderOpen size={30} className="text-acid" />
            <strong className="text-foreground">还没有本地项目</strong>
            <p className="max-w-sm text-sm text-muted-foreground">新建一个空画布，或导入包含真实素材的 .morrow 项目包。</p>
            <button className="mt-1 flex h-10 items-center gap-2 rounded-xl bg-acid px-4 font-medium text-ink transition hover:brightness-105" onClick={onCreate}><Plus size={16} /> 新建第一个项目</button>
          </div>
        )}
      </section>
    </main>
  );
}
