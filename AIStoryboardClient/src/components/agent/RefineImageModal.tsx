import { useState } from 'react';
import { LoaderCircle, Sparkles, X } from 'lucide-react';
import { assetUrl } from '../../config';
import type { GatewayModelOption } from '../../api/ai';
import { AgentParamSelector } from './AgentParamSelector';

interface Props {
  open: boolean;
  sceneNumber?: number;
  sourceImageUrl?: string;
  models: GatewayModelOption[];
  initialValue?: Record<string, string>;
  onClose: () => void;
  onSubmit: (payload: { prompt: string; model?: string; size?: string; quality?: string; sourceImageUrl?: string }) => Promise<void>;
}

/**
 * 分镜「完善图片」新窗口：展示当前分镜图，用户输入完善提示词、调整图片参数，
 * 提交后进入后端图改图队列（串行执行，右上角任务队列可见）。
 */
export function RefineImageModal({ open, sceneNumber, sourceImageUrl, models, initialValue, onClose, onSubmit }: Props) {
  const [prompt, setPrompt] = useState('');
  const [params, setParams] = useState<Record<string, string>>(() => initialValue ?? {});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const submit = async () => {
    if (!prompt.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit({
        prompt: prompt.trim(),
        model: params.imageModel || undefined,
        size: params.imageSize || undefined,
        quality: params.imageQuality || undefined,
        sourceImageUrl,
      });
      setPrompt('');
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : '入队失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <style>{'@keyframes refine-rotate { to { transform: rotate(360deg); } }'}</style>
      <div style={{ position: 'fixed', inset: 0, zIndex: 140, background: 'rgba(15,17,21,0.45)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}
        onPointerDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ width: '100%', maxWidth: 560, maxHeight: '86vh', display: 'flex', flexDirection: 'column', background: 'white', borderRadius: 16, overflow: 'hidden', boxShadow: '0 18px 48px rgba(15,17,21,0.24)' }}>
        <header style={{ padding: '12px 16px', borderBottom: '1px solid rgba(0,0,0,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            <Sparkles size={16} color="rgb(65,118,230)" />
            <strong style={{ fontSize: 14, color: 'rgb(15,17,21)' }}>完善图片 · 分镜{sceneNumber ?? ''}</strong>
          </div>
          <button onClick={onClose} style={{ border: 'none', background: 'transparent', color: 'rgb(152,158,168)', cursor: 'pointer', padding: 4, display: 'inline-flex', alignItems: 'center' }}><X size={16} /></button>
        </header>

        <div style={{ padding: '14px 16px', overflowY: 'auto', flex: 1, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {sourceImageUrl ? (
            <div style={{ display: 'flex', justifyContent: 'center' }}>
              <img src={assetUrl(sourceImageUrl)} alt={`分镜${sceneNumber ?? ''}原图`} style={{ maxWidth: 240, maxHeight: 240, borderRadius: 10, objectFit: 'contain', border: '1px solid rgba(0,0,0,0.08)' }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
            </div>
          ) : (
            <div style={{ fontSize: 12, color: 'rgb(152,158,168)', textAlign: 'center', padding: '12px 0' }}>该分镜暂无已生成图片，无法完善，请先生成图片。</div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={{ fontSize: 12, color: 'rgb(96,100,108)' }}>完善提示词</span>
            <textarea
              autoFocus
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) void submit(); }}
              placeholder="描述想如何完善这张图，例如：把色调调暖一点、换为电影质感、修正手部细节…"
              rows={4}
              style={{ width: '100%', boxSizing: 'border-box', padding: '8px 10px', borderRadius: 8, border: '1px solid rgba(0,0,0,0.1)', fontSize: 13, outline: 'none', resize: 'vertical', minHeight: 88, lineHeight: 1.5 }}
            />
          </div>

          {models.length > 0 && (
            <AgentParamSelector
              keyPrefix="image"
              models={models}
              initialValue={initialValue}
              silent
              onParamsChange={(p) => setParams(p)}
            />
          )}

          <div style={{ fontSize: 12, color: 'rgb(152,158,168)' }}>提交后按顺序完善，可随时到右上角任务队列取消排队中的任务。</div>
          {error && <div style={{ fontSize: 12, color: 'rgb(217,45,32)' }}>{error}</div>}
        </div>

        <footer style={{ padding: '12px 16px', borderTop: '1px solid rgba(0,0,0,0.06)', display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button onClick={onClose} style={{ padding: '8px 16px', borderRadius: 10, border: '1px solid rgba(0,0,0,0.08)', background: 'transparent', color: 'rgb(96,100,108)', fontSize: 13, cursor: 'pointer' }}>取消</button>
          <button disabled={!sourceImageUrl || !prompt.trim() || submitting} onClick={() => void submit()}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 18px', borderRadius: 10, border: 'none', background: sourceImageUrl && prompt.trim() && !submitting ? 'rgb(65,118,230)' : 'rgba(65,118,230,0.4)', color: 'white', fontSize: 13, cursor: sourceImageUrl && prompt.trim() && !submitting ? 'pointer' : 'not-allowed' }}>
            {submitting ? <LoaderCircle size={14} style={{ animation: 'refine-rotate 0.8s linear infinite' }} /> : <Sparkles size={14} />} 加入完善队列
          </button>
          </footer>
        </div>
      </div>
    </>
  );
}
