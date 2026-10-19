import { useRef, useState, type CSSProperties } from 'react';
import { ArrowRight, FileArchive, FolderOpen, Layers, Plus, Trash2 } from 'lucide-react';
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

// 本地项目中心：深色工作台。标题即主张，不再用 kicker / 分区小标签。
export function HomePage({ projects, loading, onCreate, onOpen, onDelete, onImport }: HomePageProps) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  return (
    <main
      className="canvas-home view-shell min-h-dvh overflow-auto px-6 py-10 md:px-14"
      style={{ background: 'radial-gradient(circle at 82% 8%, rgba(244,244,245,0.10), transparent 30%), radial-gradient(circle at 16% 90%, rgba(24,24,24,0.9), transparent 40%), var(--canvas)' }}
    >
      <input ref={fileInput} className="canvas-file-input" type="file" accept=".morrow,application/x-morrow-project,application/zip" onChange={(event) => { const file = event.target.files?.[0]; if (file) onImport(file); event.target.value = ''; }} />

      <header className="canvas-home__hero mx-auto flex max-w-6xl flex-wrap items-end justify-between gap-10 border-b border-[var(--border-dark)] pb-10">
        <div className="max-w-2xl">
          <h1 className="text-[36px] font-bold leading-[1.04] tracking-[-0.03em] text-foreground md:text-[54px]">
            设计发生在<br />你的设备上<span className="text-acid">。</span>
          </h1>
          <p className="mt-5 max-w-xl text-[13.5px] leading-[1.75] text-muted-foreground">
            项目、原图与每个派生版本都保存在本地。没有演示素材，没有伪造任务，也不会上传到未知服务。
          </p>
        </div>
        <div className="canvas-home__actions flex w-full flex-col items-stretch gap-2.5 sm:w-auto sm:flex-row sm:items-center">
          <button className="flex h-10 items-center justify-center gap-2 rounded-lg bg-acid px-4 text-[13px] font-semibold text-ink transition hover:brightness-95 active:translate-y-px" onClick={onCreate}>
            <Plus size={16} /> 新建项目
          </button>
          <button className="flex h-10 items-center justify-center gap-2 rounded-lg border border-[rgba(244,244,245,0.22)] bg-[rgba(244,244,245,0.03)] px-4 text-[13px] text-foreground transition hover:border-[rgba(244,244,245,0.4)] hover:bg-[rgba(244,244,245,0.06)] active:translate-y-px" onClick={() => fileInput.current?.click()}>
            <FileArchive size={16} /> 导入 .morrow
          </button>
        </div>
      </header>

      <section className="canvas-home__projects mx-auto max-w-6xl py-9">
        <header className="mb-5 flex items-baseline justify-between gap-4">
          <h2 className="text-[20px] font-semibold tracking-[-0.01em] text-foreground">最近项目</h2>
          <span className="font-mono text-[11px] tabular-nums text-muted-foreground">{projects.length} 个项目</span>
        </header>

        {loading ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2" aria-label="正在读取本地项目">
            {[0, 1].map((i) => (
              <div key={i} className="h-[86px] animate-pulse rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.03)]" />
            ))}
          </div>
        ) : projects.length ? (
          <div className="canvas-home__grid grid grid-cols-1 gap-3 sm:grid-cols-2">
            {projects.map((project, index) => (
              <article
                key={project.id}
                style={{ '--index': index } as CSSProperties}
                className="group flex items-center gap-2 rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.025)] p-3 transition hover:border-[rgba(244,244,245,0.34)] hover:bg-[rgba(244,244,245,0.045)]"
              >
                <button className="flex min-w-0 flex-1 items-center gap-4 rounded-lg text-left" onClick={() => onOpen(project.id)}>
                  <span className="grid h-[58px] w-[58px] shrink-0 place-items-center rounded-lg border border-[var(--border-dark)] bg-[rgba(244,244,245,0.05)]">
                    <strong className="text-[19px] font-semibold leading-none tabular-nums text-foreground">{project.canvasCount}</strong>
                    <small className="mt-1 text-[9px] leading-none tracking-[0.12em] text-muted-foreground">画布</small>
                  </span>
                  <span className="min-w-0 flex-1">
                    <strong className="block truncate text-[14.5px] font-semibold text-foreground">{project.title || 'Untitled'}</strong>
                    <small className="mt-1.5 block truncate text-[11.5px] text-muted-foreground">
                      {relativeTime(project.updatedAt)} / {project.nodeCount} 节点 / {project.assetCount} 素材
                    </small>
                  </span>
                  <ArrowRight size={16} className="shrink-0 text-muted-foreground transition group-hover:translate-x-0.5 group-hover:text-foreground" />
                </button>
                {pendingDeleteId === project.id ? (
                  <span className="flex shrink-0 gap-1">
                    <button className="rounded-md bg-[rgba(244,244,245,0.92)] px-2.5 py-1.5 text-[11px] font-semibold text-ink" onClick={() => { onDelete(project.id); setPendingDeleteId(null); }}>删除</button>
                    <button className="rounded-md border border-[var(--border-dark)] px-2.5 py-1.5 text-[11px] text-muted-foreground" onClick={() => setPendingDeleteId(null)}>取消</button>
                  </span>
                ) : (
                  <button
                    className="grid h-8 w-8 shrink-0 place-items-center rounded-md text-muted-foreground opacity-0 transition hover:bg-[rgba(244,244,245,0.08)] hover:text-foreground group-hover:opacity-100 focus-visible:opacity-100"
                    aria-label={`删除 ${project.title}`}
                    title="删除本地项目"
                    onClick={() => setPendingDeleteId(project.id)}
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </article>
            ))}
          </div>
        ) : (
          <div className="grid place-items-center gap-3 rounded-xl border border-dashed border-[var(--border-dark)] py-20 text-center">
            <span className="grid h-11 w-11 place-items-center rounded-lg border border-[var(--border-dark)] text-acid">
              <FolderOpen size={22} />
            </span>
            <strong className="text-[15px] font-semibold text-foreground">还没有本地项目</strong>
            <p className="max-w-sm text-[12.5px] leading-relaxed text-muted-foreground">新建一个空画布，或导入包含真实素材的 .morrow 项目包。</p>
            <button className="mt-1 flex h-10 items-center gap-2 rounded-lg bg-acid px-4 text-[13px] font-semibold text-ink transition hover:brightness-95" onClick={onCreate}>
              <Plus size={16} /> 新建第一个项目
            </button>
          </div>
        )}

        {projects.length > 0 && (
          <div className="mt-8 flex items-center gap-2 text-[11px] text-muted-foreground">
            <Layers size={13} />
            全部数据保存在本机，可随时导出 .morrow 项目包迁移。
          </div>
        )}
      </section>
    </main>
  );
}
