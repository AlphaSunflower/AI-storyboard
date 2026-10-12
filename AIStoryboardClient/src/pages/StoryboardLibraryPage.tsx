import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  Camera,
  ChevronLeft,
  ChevronRight,
  Clapperboard,
  ExternalLink,
  Film,
  FolderOpen,
  Image as ImageIcon,
  Images,
  Play,
  RefreshCw,
  Search,
  X,
} from 'lucide-react';
import { assetUrl } from '../config';
import { getSceneImageUrls } from '../lib/sceneImages';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { Tooltip } from '../components/common/Tooltip';
import type { ProjectResponse, SceneResponse } from '../api/projects';
import './StoryboardLibraryPage.css';

type MediaFilter = 'all' | 'image' | 'video' | 'pending';

interface StoryboardEntry {
  project: ProjectResponse;
  scene: SceneResponse;
}

const mediaFilterLabel: Record<MediaFilter, string> = {
  all: '全部',
  image: '已出图',
  video: '已出片',
  pending: '待生成',
};

export function StoryboardLibraryPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const embedded = new URLSearchParams(location.search).get('embedded') === '1';
  const projects = useProjectStore((s) => s.projects);
  const loadProjects = useProjectStore((s) => s.loadProjects);
  const [query, setQuery] = useState('');
  const [projectFilter, setProjectFilter] = useState('all');
  const [mediaFilter, setMediaFilter] = useState<MediaFilter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void useAuthStore.getState().checkAuth().then((authenticated) => {
      if (!authenticated) {
        navigate('/login', { replace: true });
        return;
      }
      setLoading(true);
      return loadProjects()
        .then(() => {
          if (alive) setError(null);
        })
        .catch(() => {
          if (alive) setError('分镜加载失败，请稍后重试');
        })
        .finally(() => {
          if (alive) setLoading(false);
        });
    });
    return () => {
      alive = false;
    };
  }, [loadProjects, navigate]);

  const allEntries = useMemo<StoryboardEntry[]>(
    () => projects.flatMap((project) => (project.scenes || []).map((scene) => ({ project, scene }))),
    [projects],
  );

  const availableProjects = useMemo(
    () => projects.filter((project) => (project.scenes || []).length > 0),
    [projects],
  );

  const filteredEntries = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return allEntries.filter(({ project, scene }) => {
      if (projectFilter !== 'all' && project.id !== projectFilter) return false;
      const imageUrls = getSceneImageUrls(scene);
      const hasVideo = Boolean(scene.videoUrl);
      const isPending = scene.imageStatus === 'generating' || scene.videoStatus === 'generating';
      if (mediaFilter === 'image' && imageUrls.length === 0) return false;
      if (mediaFilter === 'video' && !hasVideo) return false;
      if (mediaFilter === 'pending' && (imageUrls.length > 0 || hasVideo || !isPending)) return false;
      if (!keyword) return true;
      return [
        project.name,
        project.description,
        String(scene.sceneNumber),
        scene.scriptContent,
        scene.imagePrompt,
        scene.videoPrompt,
        scene.shotType,
        scene.cameraMovement,
      ].some((value) => value?.toLowerCase().includes(keyword));
    });
  }, [allEntries, mediaFilter, projectFilter, query]);

  const groupedEntries = useMemo(() => {
    const map = new Map<string, { project: ProjectResponse; entries: StoryboardEntry[] }>();
    for (const entry of filteredEntries) {
      const group = map.get(entry.project.id);
      if (group) group.entries.push(entry);
      else map.set(entry.project.id, { project: entry.project, entries: [entry] });
    }
    return [...map.values()];
  }, [filteredEntries]);

  const selectedEntry = useMemo(
    () => allEntries.find(({ project, scene }) => `${project.id}:${scene.id}` === selectedKey) ?? null,
    [allEntries, selectedKey],
  );

  const mediaCount = useMemo(
    () => allEntries.filter(({ scene }) => getSceneImageUrls(scene).length > 0 || scene.videoUrl).length,
    [allEntries],
  );

  const resetFilters = () => {
    setQuery('');
    setProjectFilter('all');
    setMediaFilter('all');
  };

  const openEditor = (projectId?: string, sceneId?: string) => {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (sceneId) params.set('sceneId', sceneId);
    if (embedded) params.set('embedded', '1');
    navigate(`/editor?${params.toString()}`);
  };

  return (
    <div className="sb-page">
      <header className="sb-header">
        <div className="sb-header-inner">
          {!embedded && (
            <button className="sb-back" onClick={() => navigate('/chat')}>
              <ArrowLeft size={18} strokeWidth={1.8} />
              <span>返回对话</span>
            </button>
          )}
          <div className="sb-title-wrap">
            <span className="sb-title-icon"><Clapperboard size={20} strokeWidth={1.8} /></span>
            <div>
              <h1>分镜库</h1>
              <p>跨项目查看与检索全部分镜</p>
            </div>
          </div>
          <div className="sb-header-actions">
            <Tooltip side="bottom" label="刷新">
            <button
              className="sb-icon-button"
              aria-label="刷新分镜"
              disabled={loading}
              onClick={() => {
                setLoading(true);
                loadProjects()
                  .then(() => setError(null))
                  .catch(() => setError('分镜加载失败，请稍后重试'))
                  .finally(() => setLoading(false));
              }}
            >
              <RefreshCw size={17} strokeWidth={1.8} className={loading ? 'sb-spin' : undefined} />
            </button>
            </Tooltip>
            {!embedded && (
              <button
                className="sb-primary"
                onClick={() => {
                  const first = allEntries[0];
                  if (first) openEditor(first.project.id, first.scene.id);
                  else openEditor();
                }}
              >
                <FolderOpen size={16} strokeWidth={1.8} />
                打开编辑器
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="sb-main">
        <div className="sb-shell">
          <section className="sb-summary">
            <div className="sb-summary-copy">
              <span className="sb-eyebrow">Storyboard overview</span>
              <h2>{allEntries.length} 个分镜，集中在一个视图里</h2>
              <p>按项目筛选、搜索内容，并直接预览图片、视频和提示词。</p>
            </div>
            <div className="sb-stats">
              <Stat value={availableProjects.length} label="项目" />
              <Stat value={allEntries.length} label="分镜" />
              <Stat value={mediaCount} label="已产出" />
            </div>
          </section>

          <section className="sb-toolbar" aria-label="分镜筛选">
            <label className="sb-search">
              <Search size={16} strokeWidth={1.9} />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索项目、分镜内容或提示词"
              />
              {query && (
                <Tooltip label="清除搜索">
                <button onClick={() => setQuery('')} aria-label="清除搜索">
                  <X size={14} strokeWidth={2} />
                </button>
                </Tooltip>
              )}
            </label>

            <select
              className="sb-project-select"
              value={projectFilter}
              onChange={(event) => setProjectFilter(event.target.value)}
              aria-label="按项目筛选"
            >
              <option value="all">全部项目</option>
              {availableProjects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}（{project.scenes.length}）
                </option>
              ))}
            </select>

            <div className="sb-segmented">
              {(Object.keys(mediaFilterLabel) as MediaFilter[]).map((filter) => (
                <button
                  key={filter}
                  className={mediaFilter === filter ? 'is-active' : undefined}
                  onClick={() => setMediaFilter(filter)}
                >
                  {mediaFilterLabel[filter]}
                </button>
              ))}
            </div>

            <span className="sb-result-count">{filteredEntries.length} 项</span>
          </section>

          {error && (
            <div className="sb-error">
              <span>{error}</span>
              <button onClick={() => loadProjects().catch(() => setError('分镜加载失败，请稍后重试'))}>重试</button>
            </div>
          )}

          {loading && allEntries.length === 0 && (
            <div className="sb-loading">
              <RefreshCw size={20} strokeWidth={1.8} className="sb-spin" />
              <span>正在加载分镜</span>
            </div>
          )}

          {!loading && groupedEntries.length === 0 && (
            <div className="sb-empty">
              <span className="sb-empty-icon"><Images size={30} strokeWidth={1.5} /></span>
              <h3>{allEntries.length === 0 ? '还没有分镜' : '没有匹配的分镜'}</h3>
              <p>{allEntries.length === 0 ? '在对话中生成分镜后，它们会汇集到这里。' : '尝试更换关键词或筛选条件。'}</p>
              <div className="sb-empty-actions">
                <button className="sb-secondary" onClick={resetFilters}>清除筛选</button>
                {!embedded && (
                  <button className="sb-primary" onClick={() => navigate('/chat')}>返回对话</button>
                )}
              </div>
            </div>
          )}

          {groupedEntries.map(({ project, entries }) => (
            <section className="sb-project-section" key={project.id}>
              <div className="sb-project-heading">
                <div>
                  <span className="sb-project-kicker">项目分镜</span>
                  <h2>{project.name}</h2>
                </div>
                <div className="sb-project-meta">
                  <span>{entries.length} 个分镜</span>
                  <button onClick={() => openEditor(project.id)}>
                    打开项目
                    <ExternalLink size={13} strokeWidth={1.9} />
                  </button>
                </div>
              </div>

              <div className="sb-grid">
                {entries.map(({ project: entryProject, scene }) => (
                  <StoryboardCard
                    key={`${entryProject.id}:${scene.id}`}
                    project={entryProject}
                    scene={scene}
                    onOpen={() => setSelectedKey(`${entryProject.id}:${scene.id}`)}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      </main>

      {selectedEntry && (
        <StoryboardDetail
          entry={selectedEntry}
          onClose={() => setSelectedKey(null)}
          onOpenEditor={() => openEditor(selectedEntry.project.id, selectedEntry.scene.id)}
        />
      )}
      {embedded && <MobileBottomNav embeddedEditor />}
    </div>
  );
}

function Stat({ value, label }: { value: number; label: string }) {
  return (
    <div className="sb-stat">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function StoryboardCard({
  project,
  scene,
  onOpen,
}: {
  project: ProjectResponse;
  scene: SceneResponse;
  onOpen: () => void;
}) {
  const imageUrls = getSceneImageUrls(scene);
  const cover = imageUrls[0];
  const hasVideo = Boolean(scene.videoUrl);
  const generating = scene.imageStatus === 'generating' || scene.videoStatus === 'generating';
  const excerpt = scene.scriptContent || scene.imagePrompt || scene.videoPrompt || '暂无分镜描述';

  return (
    <button className="sb-card" onClick={onOpen}>
      <div className="sb-card-media">
        {cover ? (
          <img src={assetUrl(cover)} alt={`${project.name} 分镜 ${scene.sceneNumber}`} loading="lazy" />
        ) : hasVideo ? (
          <video src={assetUrl(scene.videoUrl)} muted playsInline preload="metadata" />
        ) : (
          <div className="sb-card-placeholder">
            <Camera size={24} strokeWidth={1.5} />
            <span>暂无画面</span>
          </div>
        )}
        <div className="sb-card-badges">
          <span className="sb-scene-number">#{String(scene.sceneNumber).padStart(2, '0')}</span>
          {hasVideo && <span className="sb-media-badge sb-media-badge--video"><Play size={11} fill="currentColor" /> 视频</span>}
          {!hasVideo && imageUrls.length > 0 && <span className="sb-media-badge"><ImageIcon size={11} /> 图片</span>}
          {generating && <span className="sb-media-badge sb-media-badge--pending">生成中</span>}
          {imageUrls.length > 1 && <span className="sb-media-badge">+{imageUrls.length - 1}</span>}
        </div>
      </div>
      <div className="sb-card-body">
        <div className="sb-card-project">{project.name}</div>
        <p>{excerpt}</p>
        <div className="sb-card-footer">
          <span>{scene.shotType || '未设置景别'}</span>
          <span>{scene.cameraMovement || '固定镜头'}</span>
          <span>{formatDate(scene.updatedAt || project.updatedAt)}</span>
        </div>
      </div>
    </button>
  );
}

function StoryboardDetail({
  entry,
  onClose,
  onOpenEditor,
}: {
  entry: StoryboardEntry;
  onClose: () => void;
  onOpenEditor: () => void;
}) {
  const { project, scene } = entry;
  const imageUrls = getSceneImageUrls(scene);
  const [mediaKind, setMediaKind] = useState<'image' | 'video'>(scene.videoUrl && imageUrls.length === 0 ? 'video' : 'image');
  const [imageIndex, setImageIndex] = useState(0);
  const activeImage = imageUrls[Math.min(imageIndex, Math.max(imageUrls.length - 1, 0))];

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  useEffect(() => {
    setMediaKind(scene.videoUrl && imageUrls.length === 0 ? 'video' : 'image');
    setImageIndex(0);
  }, [scene.id, scene.videoUrl, imageUrls.length]);

  const previousImage = () => setImageIndex((index) => (index - 1 + imageUrls.length) % imageUrls.length);
  const nextImage = () => setImageIndex((index) => (index + 1) % imageUrls.length);

  return (
    <div className="sb-modal-backdrop" onMouseDown={onClose}>
      <div className="sb-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog" aria-modal="true" aria-label={`分镜 ${scene.sceneNumber}`}>
        <header className="sb-modal-header">
          <div className="sb-modal-title">
            <span>{project.name}</span>
            <h2>分镜 {String(scene.sceneNumber).padStart(2, '0')}</h2>
          </div>
          <div className="sb-modal-actions">
            <button className="sb-primary" onClick={onOpenEditor}>
              <ExternalLink size={15} strokeWidth={1.9} />
              在编辑器中打开
            </button>
            <Tooltip label="关闭">
            <button className="sb-icon-button" onClick={onClose} aria-label="关闭详情">
              <X size={18} strokeWidth={1.9} />
            </button>
            </Tooltip>
          </div>
        </header>

        <div className="sb-detail">
          <section className="sb-detail-preview">
            <div className="sb-media-stage">
              {mediaKind === 'video' && scene.videoUrl ? (
                <video src={assetUrl(scene.videoUrl)} controls autoPlay playsInline />
              ) : activeImage ? (
                <>
                  <img src={assetUrl(activeImage)} alt={`${project.name} 分镜 ${scene.sceneNumber}`} />
                  {imageUrls.length > 1 && (
                    <>
                      <button className="sb-carousel-button sb-carousel-button--left" onClick={previousImage} aria-label="上一张">
                        <ChevronLeft size={20} strokeWidth={2} />
                      </button>
                      <button className="sb-carousel-button sb-carousel-button--right" onClick={nextImage} aria-label="下一张">
                        <ChevronRight size={20} strokeWidth={2} />
                      </button>
                    </>
                  )}
                </>
              ) : (
                <div className="sb-media-empty">
                  <Camera size={34} strokeWidth={1.4} />
                  <span>这个分镜还没有画面</span>
                </div>
              )}
            </div>

            {(imageUrls.length > 0 || scene.videoUrl) && (
              <div className="sb-media-toolbar">
                <div className="sb-media-tabs">
                  {imageUrls.length > 0 && (
                    <button className={mediaKind === 'image' ? 'is-active' : undefined} onClick={() => setMediaKind('image')}>
                      <ImageIcon size={14} strokeWidth={1.8} />
                      图片 {imageUrls.length}
                    </button>
                  )}
                  {scene.videoUrl && (
                    <button className={mediaKind === 'video' ? 'is-active' : undefined} onClick={() => setMediaKind('video')}>
                      <Film size={14} strokeWidth={1.8} />
                      视频
                    </button>
                  )}
                </div>
                {mediaKind === 'image' && imageUrls.length > 1 && (
                  <span className="sb-image-count">{imageIndex + 1} / {imageUrls.length}</span>
                )}
              </div>
            )}
          </section>

          <aside className="sb-detail-info">
            <div className="sb-detail-status">
              <StatusPill label="图片" status={scene.imageStatus} active={imageUrls.length > 0} />
              <StatusPill label="视频" status={scene.videoStatus} active={Boolean(scene.videoUrl)} />
            </div>

            <DetailBlock title="分镜脚本" text={scene.scriptContent} />
            <DetailBlock title="画面提示词" text={scene.imagePrompt} />
            <DetailBlock title="视频提示词" text={scene.videoPrompt} />

            <div className="sb-meta-grid">
              <MetaItem label="景别" value={scene.shotType} />
              <MetaItem label="运镜" value={scene.cameraMovement} />
              <MetaItem label="比例" value={scene.videoAspectRatio || project.aspectRatio} />
              <MetaItem label="时长" value={scene.duration ? `${scene.duration} 秒` : ''} />
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

function StatusPill({ label, status, active }: { label: string; status: string; active: boolean }) {
  const text = active ? '已生成' : status === 'generating' ? '生成中' : status === 'failed' ? '失败' : '未生成';
  const tone = active ? 'ready' : status === 'generating' ? 'pending' : status === 'failed' ? 'failed' : 'idle';
  return <span className={`sb-status sb-status--${tone}`}>{label} · {text}</span>;
}

function DetailBlock({ title, text }: { title: string; text?: string | null }) {
  if (!text?.trim()) return null;
  return (
    <section className="sb-detail-block">
      <h3>{title}</h3>
      <p>{text}</p>
    </section>
  );
}

function MetaItem({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value || '未设置'}</strong>
    </div>
  );
}

function formatDate(value: string): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}
