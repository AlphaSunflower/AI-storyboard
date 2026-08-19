import { useEffect, useState } from 'react';
import { useAgentStore } from '@/stores/agentStore';
import { projectApi, type ProjectResponse } from '@/api/projects';
import { FolderOpen, Folder, RefreshCw, Check } from 'lucide-react';

/** 项目选择弹窗:按用户拉取项目列表(GET /api/projects,JWT 自动带 userId),点选切换项目(会话随之切换) */
export function ProjectModal() {
  const { projectId, switchProject, setActiveModal } = useAgentStore();
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await projectApi.list();
      setProjects(res.data.data ?? []);
    } catch {
      setError('项目列表加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const current = projects.find((p) => p.id === projectId);

  const handleSelect = (id: string) => {
    if (id === projectId) return;
    void switchProject(id).then(() => setActiveModal(null)); // 选完即收起弹窗
  };

  const fmtDate = (iso: string) => {
    try {
      return new Date(iso).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
    } catch {
      return '';
    }
  };

  return (
    <div className="text-[15px]" style={{ color: 'var(--color-body)' }}>
      {/* 当前项目 */}
      {current ? (
        <div className="flex items-center gap-2.5 mb-4 px-3.5 py-3 rounded-[10px]" style={{ background: 'var(--color-surface-card)' }}>
          <FolderOpen size={15} style={{ color: 'var(--color-primary)' }} />
          <div className="min-w-0">
            <div className="text-[14px] font-medium truncate" style={{ color: 'var(--color-ink)' }}>{current.name}</div>
            <div className="text-[12px]" style={{ color: 'var(--color-muted)' }}>当前项目 · {current.scenes?.length ?? 0} 个分镜</div>
          </div>
        </div>
      ) : (
        <p className="mb-4 leading-relaxed" style={{ color: 'var(--color-muted)' }}>
          选择项目后,会话列表将切换到该项目下的对话(与 AI 分镜系统共享数据)。
        </p>
      )}

      {/* 加载骨架 */}
      {loading && (
        <div className="flex flex-col gap-2.5">
          {[0, 1, 2].map((i) => (
            <div key={i} className="rounded-[10px] p-3.5" style={{ border: '1px solid var(--color-border)', background: 'var(--color-surface-soft)', opacity: 0.6 - i * 0.15 }}>
              <div className="h-3.5 w-1/3 rounded mb-2" style={{ background: 'var(--color-border)' }} />
              <div className="h-3 w-1/2 rounded" style={{ background: 'var(--color-border)' }} />
            </div>
          ))}
        </div>
      )}

      {/* 错误态 */}
      {!loading && error && (
        <div className="rounded-[10px] px-4 py-3 mb-3 text-[14px]" style={{ background: 'rgba(198,69,69,0.06)', color: 'var(--color-error)', border: '1px solid rgba(198,69,69,0.1)' }}>
          {error}
          <button onClick={() => void load()} className="flex items-center gap-1.5 mt-2 px-3 py-1.5 rounded-[8px] text-[13px]"
            style={{ border: '1px solid var(--color-border)', background: 'white', color: 'var(--color-body)', cursor: 'pointer' }}>
            <RefreshCw size={12} /> 重试
          </button>
        </div>
      )}

      {/* 空态 */}
      {!loading && !error && projects.length === 0 && (
        <div className="flex flex-col items-center py-10">
          <Folder size={28} style={{ color: 'var(--color-muted-soft)' }} />
          <p className="text-[14px] mt-3" style={{ color: 'var(--color-muted)' }}>暂无项目,请先在分镜系统中创建</p>
        </div>
      )}

      {/* 项目列表 */}
      {!loading && !error && projects.length > 0 && (
        <div className="flex flex-col gap-3 max-h-[58vh] overflow-y-auto">
          {projects.map((p) => {
            const active = p.id === projectId;
            return (
              <button key={p.id} onClick={() => handleSelect(p.id)}
                className="flex items-center gap-3 px-5 py-4 rounded-[12px] text-left transition-all active:scale-[0.99]"
                style={{
                  border: `1px solid ${active ? 'var(--color-primary)' : 'var(--color-border)'}`,
                  background: active ? 'rgba(204,120,92,0.06)' : 'white',
                  cursor: 'pointer',
                }}>
                <div className="flex-1 min-w-0">
                  <div className="text-[16px] font-medium truncate" style={{ color: 'var(--color-ink)' }}>{p.name || '未命名项目'}</div>
                  <div className="text-[13px] mt-1" style={{ color: 'var(--color-muted)' }}>
                    {p.scenes?.length ?? 0} 个分镜 · 更新于 {fmtDate(p.updatedAt)}
                  </div>
                </div>
                {active && <Check size={16} style={{ color: 'var(--color-primary)' }} />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
