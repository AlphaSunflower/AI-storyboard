import { useEffect, useState } from 'react';
import { Check, CircleStop, ListTodo, LoaderCircle, X } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';

const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
};

const ACTIVE = new Set(['queued', 'running']);

/** /chat 右上角任务队列：展示分镜视频生成排队/进度，可取消 queued 任务。 */
export function TaskQueuePanel() {
  const [open, setOpen] = useState(false);
  const queue = useAgentStore((s) => s.storyboardVideoQueue);
  const refetch = useAgentStore((s) => s.refetchStoryboardVideoQueue);
  const cancel = useAgentStore((s) => s.cancelStoryboardVideoQueue);
  const imageQueue = useAgentStore((s) => s.storyboardImageQueue);
  const refetchImage = useAgentStore((s) => s.refetchStoryboardImageQueue);
  const cancelImage = useAgentStore((s) => s.cancelStoryboardImageQueue);
  const projectId = useProjectStore((s) => s.currentProject?.id);
  const scenes = useProjectStore((s) => s.scenes);

  // 有进行中任务时轮询刷进度（5s）
  const activeVideo = queue.filter((t) => ACTIVE.has(t.status));
  const activeImage = imageQueue.filter((t) => ACTIVE.has(t.status));
  const activeCount = activeVideo.length + activeImage.length;
  useEffect(() => {
    if (!projectId || activeCount === 0) return;
    const timer = setInterval(() => { void refetch(projectId); void refetchImage(projectId); }, 5000);
    return () => clearInterval(timer);
  }, [projectId, activeCount, refetch, refetchImage]);

  // 关闭下拉时也保留队列数据（供分镜行状态复用）
  return (
    <>
      <style>{'@keyframes queue-rotate { to { transform: rotate(360deg); } }'}</style>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        title="任务队列"
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 4, border: 'none',
          background: 'transparent', color: open ? 'rgb(65, 118, 230)' : 'rgb(96, 100, 108)',
          fontSize: 13, cursor: 'pointer', padding: '6px 10px', borderRadius: 8,
          position: 'relative',
        }}
      >
        <ListTodo size={15} /> 任务队列
        {activeCount > 0 && (
          <span style={{
            position: 'absolute', top: -2, right: -2, minWidth: 16, height: 16, borderRadius: 8,
            background: '#e2544b', color: 'white', fontSize: 10, lineHeight: '16px', textAlign: 'center', padding: '0 4px',
          }}>{activeCount}</span>
        )}
      </button>
      {open && (
        <div
          style={{
            position: 'absolute', top: '100%', right: 0, marginTop: 4, width: 320, maxHeight: 420,
            overflowY: 'auto', background: 'white', border: '1px solid rgba(0, 0, 0, 0.08)',
            borderRadius: 12, boxShadow: '0 12px 32px rgba(15, 17, 21, 0.16)', padding: 8, zIndex: 110,
          }}
          role="dialog"
          aria-label="任务队列"
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 8px' }}>
            <strong style={{ fontSize: 13, color: 'rgb(15, 17, 21)' }}>任务队列</strong>
            <small style={{ fontSize: 12, color: 'rgb(152, 158, 168)' }}>
              {activeCount ? `${activeCount} 个进行中` : '空闲'}
            </small>
          </div>
          {activeVideo.length === 0 && activeImage.length === 0 ? (
            <div style={{ fontSize: 13, color: 'rgb(152, 158, 168)', padding: '18px 10px', textAlign: 'center' }}>
              暂无任务
            </div>
          ) : (
            <>
              {activeVideo.length > 0 && (
                <div style={{ padding: '4px 8px 2px', fontSize: 11, color: 'rgb(152, 158, 168)' }}>视频生成</div>
              )}
              {activeVideo.map((t) => renderRow(t, '视频', scenes, cancel))}
              {activeImage.length > 0 && (
                <div style={{ padding: '8px 8px 2px', fontSize: 11, color: 'rgb(152, 158, 168)' }}>完善图片</div>
              )}
              {activeImage.map((t) => renderRow(t, '图片', scenes, cancelImage))}
            </>
          )}
        </div>
      )}
    </>
  );
}

type QueueRow = { id: string; sceneId: string; status: string; progress: number | null; error: string | null };

/** 单条任务行：视频（视频生成）与图片（完善图片）共用。 */
function renderRow(t: QueueRow, kind: '视频' | '图片', scenes: { id: string; sceneNumber?: number }[], onCancel: (id: string) => void) {
  const active = ACTIVE.has(t.status);
  const sceneNum = scenes.find((s) => s.id === t.sceneId)?.sceneNumber;
  return (
    <div
      key={`${kind}-${t.id}`}
      style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '9px 10px',
        borderRadius: 8, background: active ? 'rgba(65, 118, 230, 0.04)' : 'transparent',
      }}
    >
      <span style={{ display: 'inline-flex', alignItems: 'center', color: t.status === 'completed' ? 'rgb(15, 150, 80)' : t.status === 'failed' ? 'rgb(217, 45, 32)' : 'rgb(65, 118, 230)' }}>
        {t.status === 'completed' ? <Check size={14} />
          : t.status === 'failed' ? <X size={14} />
          : <LoaderCircle size={14} style={active ? { animation: 'queue-rotate 0.8s linear infinite' } : undefined} />}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <strong style={{ fontSize: 13, color: 'rgb(15, 17, 21)' }}>
            分镜 {sceneNum ?? ''} · {kind}
          </strong>
          <small style={{ fontSize: 11, color: 'rgb(96, 100, 108)' }}>{STATUS_LABEL[t.status] ?? t.status}</small>
        </div>
        <div style={{ height: 3, background: 'rgba(0, 0, 0, 0.08)', borderRadius: 2, marginTop: 5, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${Math.max(3, t.progress ?? 0)}%`, background: 'rgb(65, 118, 230)', borderRadius: 2, transition: 'width 0.4s' }} />
        </div>
        {t.error && <small style={{ fontSize: 11, color: 'rgb(217, 45, 32)' }}>{t.error}</small>}
      </div>
      {t.status === 'queued' && (
        <button
          type="button"
          title="取消生成"
          onClick={() => void onCancel(t.id)}
          style={{ border: 'none', background: 'transparent', color: 'rgb(96, 100, 108)', cursor: 'pointer', padding: 4 }}
        ><CircleStop size={14} /></button>
      )}
      {t.status === 'running' && (
        <span title="上游已受理，无法取消" style={{ fontSize: 10, color: 'rgb(152, 158, 168)', whiteSpace: 'nowrap' }}>无法取消</span>
      )}
    </div>
  );
}
