import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '@/stores/agentStore';
import { projectApi, type ProjectResponse, type SceneResponse } from '@/api/projects';
import { assetUrl } from '@/config';
import { ImagePreviewModal } from '@/components/ImagePreviewModal';
import { ChevronLeft, Film, FolderOpen, RefreshCw, Image as ImageIcon, Video as VideoIcon } from 'lucide-react';

/** 分镜小窗(点击侧栏「分镜」弹出):两级导航 列表页 ↔ 预览页,数据与 AI 分镜系统共享(只读) */
export function StoryboardModal() {
  const { projectId, setActiveModal } = useAgentStore();
  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 当前预览的分镜 id(null = 列表页)
  const [previewId, setPreviewId] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const previewRef = useRef<HTMLDivElement>(null);

  const load = async () => {
    if (!projectId) { setProject(null); return; }
    setLoading(true);
    setError(null);
    try {
      const res = await projectApi.get(projectId);
      setProject(res.data.data);
    } catch {
      setError('分镜加载失败,请检查网络后重试');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, [projectId]);

  // 项目切换时回到列表页
  useEffect(() => { setPreviewId(null); }, [projectId]);

  const scenes = [...(project?.scenes ?? [])].sort((a, b) => a.sceneNumber - b.sceneNumber);
  const preview = previewId ? scenes.find((s) => s.id === previewId) ?? null : null;

  useGSAP(() => {
    if (!previewId) return;
    // 列表 ↔ 预览切换过渡
    gsap.fromTo('.sb-preview', { x: 24, opacity: 0 }, { x: 0, opacity: 1, duration: 0.22, ease: 'power2.out' });
  }, { dependencies: [previewId], scope: previewRef });

  // 未选项目:引导去项目弹窗
  if (!projectId) {
    return (
      <div className="flex flex-col items-center py-12 text-center">
        <FolderOpen size={30} style={{ color: 'var(--color-muted-soft)' }} />
        <p className="text-[15px] mt-3 mb-4" style={{ color: 'var(--color-muted)' }}>请先选择一个项目,再查看分镜</p>
        <button onClick={() => setActiveModal('project')}
          className="px-5 py-2.5 rounded-[10px] text-[15px] font-medium transition-all hover:brightness-110 active:scale-[0.98]"
          style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)' }}>
          选择项目
        </button>
      </div>
    );
  }

  return (
    <div className="text-[15px]" style={{ color: 'var(--color-body)' }}>
      {/* 项目名 + 刷新 */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-[14px] font-medium truncate" style={{ color: 'var(--color-muted)' }}>
          {project?.name ?? '加载中…'}
        </span>
        <button onClick={() => void load()} title="刷新分镜"
          className="p-1.5 rounded-lg hover:bg-[var(--color-surface-soft)] transition-colors"
          style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer' }}>
          <RefreshCw size={13} />
        </button>
      </div>

      {loading && (
        <div className="flex flex-col gap-2.5">
          {[0, 1, 2].map((i) => (
            <div key={i} className="rounded-[10px] p-3" style={{ border: '1px solid var(--color-border)', background: 'var(--color-surface-soft)', opacity: 0.6 - i * 0.15 }}>
              <div className="h-3.5 w-1/4 rounded mb-2" style={{ background: 'var(--color-border)' }} />
              <div className="h-3 w-2/3 rounded" style={{ background: 'var(--color-border)' }} />
            </div>
          ))}
        </div>
      )}

      {!loading && error && (
        <div className="rounded-[10px] px-4 py-3 text-[14px]" style={{ background: 'rgba(198,69,69,0.06)', color: 'var(--color-error)', border: '1px solid rgba(198,69,69,0.1)' }}>
          {error}
          <button onClick={() => void load()} className="flex items-center gap-1.5 mt-2 px-3 py-1.5 rounded-[8px] text-[13px]"
            style={{ border: '1px solid var(--color-border)', background: 'white', color: 'var(--color-body)', cursor: 'pointer' }}>
            <RefreshCw size={12} /> 重试
          </button>
        </div>
      )}

      {!loading && !error && scenes.length === 0 && (
        <div className="flex flex-col items-center py-10">
          <Film size={28} style={{ color: 'var(--color-muted-soft)' }} />
          <p className="text-[14px] mt-3" style={{ color: 'var(--color-muted)' }}>该项目暂无分镜,去分镜系统生成吧</p>
        </div>
      )}

      {/* 预览页 */}
      {!loading && !error && preview && (
        <div className="sb-preview" ref={previewRef}>
          <button onClick={() => setPreviewId(null)}
            className="flex items-center gap-1 px-2 py-1.5 rounded-[8px] mb-3 text-[14px] transition-colors hover:bg-[var(--color-surface-soft)]"
            style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer' }}>
            <ChevronLeft size={15} /> 返回分镜列表
          </button>

          <div className="rounded-[12px] p-4 mb-3" style={{ background: 'var(--color-surface-card)' }}>
            <div className="text-[14px] font-medium mb-1.5" style={{ color: 'var(--color-ink)' }}>分镜 {preview.sceneNumber} · 脚本</div>
            <p className="text-[15px] leading-[1.75] whitespace-pre-wrap" style={{ color: 'var(--color-body)' }}>
              {preview.scriptContent || '（暂无脚本）'}
            </p>
          </div>

          {/* 图片:多图(imageUrls 逗号分隔)优先,否则单 imageUrl */}
          {(() => {
            const urls = (preview.imageUrls || '').split(',').map((u) => u.trim()).filter(Boolean);
            const imgs = urls.length > 0 ? urls : (preview.imageUrl ? [preview.imageUrl] : []);
            if (imgs.length === 0) return null;
            return (
              <div className="mb-3">
                <div className="flex flex-wrap gap-2.5">
                  {imgs.map((u, i) => (
                    <img key={i} src={assetUrl(u)} alt={`分镜 ${preview.sceneNumber} 图片 ${i + 1}`}
                      onClick={() => setPreviewUrl(u)}
                      style={{ width: 96, height: 96, objectFit: 'cover', borderRadius: 10, border: '1px solid var(--color-border)', cursor: 'zoom-in' }}
                      onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                  ))}
                </div>
                <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
              </div>
            );
          })()}

          {preview.videoUrl && (
            <div className="mb-3">
              <video src={assetUrl(preview.videoUrl)} controls style={{ maxWidth: '100%', borderRadius: 10, display: 'block' }} />
            </div>
          )}

          {/* 镜头参数 */}
          <div className="rounded-[10px] px-4 py-3" style={{ border: '1px solid var(--color-border)', background: 'white' }}>
            <div className="flex flex-col gap-1.5 text-[13px]" style={{ color: 'var(--color-muted)' }}>
              {preview.shotType && <div><span className="font-medium" style={{ color: 'var(--color-body)' }}>景别:</span> {preview.shotType}</div>}
              {preview.cameraMovement && <div><span className="font-medium" style={{ color: 'var(--color-body)' }}>运镜:</span> {preview.cameraMovement}</div>}
              {preview.duration > 0 && <div><span className="font-medium" style={{ color: 'var(--color-body)' }}>时长:</span> {preview.duration} 秒</div>}
              {preview.negativePrompt && <div><span className="font-medium" style={{ color: 'var(--color-body)' }}>负面提示词:</span> {preview.negativePrompt}</div>}
            </div>
          </div>
        </div>
      )}

      {/* 列表页 */}
      {!loading && !error && !preview && scenes.length > 0 && (
        <div className="flex flex-col gap-3 max-h-[58vh] overflow-y-auto">
          {scenes.map((s: SceneResponse) => (
            <button key={s.id} onClick={() => setPreviewId(s.id)}
              className="flex items-center gap-3.5 px-5 py-4 rounded-[12px] text-left transition-all hover:brightness-[0.98] active:scale-[0.99]"
              style={{ border: '1px solid var(--color-border)', background: 'white', cursor: 'pointer' }}>
              {/* 缩略图:图 > 视频 > 占位 */}
              {s.imageUrl ? (
                <img src={assetUrl(s.imageUrl)} alt="" style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 10, border: '1px solid var(--color-border)' }} />
              ) : s.videoUrl ? (
                <video src={assetUrl(s.videoUrl)} style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 10 }} muted />
              ) : (
                <div className="flex items-center justify-center" style={{ width: 56, height: 56, borderRadius: 10, background: 'var(--color-surface-soft)' }}>
                  <Film size={18} style={{ color: 'var(--color-muted-soft)' }} />
                </div>
              )}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-[15px] font-semibold" style={{ color: 'var(--color-ink)' }}>分镜 {s.sceneNumber}</span>
                  {s.imageStatus === 'completed' && <ImageIcon size={13} style={{ color: 'var(--color-success)' }} />}
                  {s.videoStatus === 'completed' && <VideoIcon size={13} style={{ color: 'var(--color-success)' }} />}
                </div>
                <div className="text-[14px] truncate" style={{ color: 'var(--color-muted)' }}>
                  {s.scriptContent || '（暂无脚本）'}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
