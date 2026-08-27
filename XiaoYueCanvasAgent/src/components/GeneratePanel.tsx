import { useState } from 'react';
import { useStore } from '@xyflow/react';
import { ArrowUp, LoaderCircle, Sparkles, X } from 'lucide-react';
import { submitImageGeneration } from '../services/jobRunner';
import type { CanvasNode } from '../types';

interface GeneratePanelProps {
  node: CanvasNode;
  onClose: () => void;
}

export function GeneratePanel({ node, onClose }: GeneratePanelProps) {
  const zoom = useStore((state) => state.transform[2]);
  const viewportX = useStore((state) => state.transform[0]);
  const canvasWidth = useStore((state) => state.width);
  const [prompt, setPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const notify = (message: string) => {
    setFeedback(message);
    window.setTimeout(() => setFeedback(null), 2200);
  };

  const nodeCenter = viewportX + (node.position.x + (node.width ?? 250) / 2) * zoom;
  const naturalWidth = 430;
  const width = Math.min(naturalWidth, Math.max(280, canvasWidth - 24));
  const naturalLeft = nodeCenter - width / 2;
  const left = Math.min(Math.max(12, naturalLeft), Math.max(12, canvasWidth - width - 12));
  const panelStyle = { '--inverse-zoom': 1 / zoom, '--floating-shift': `${(left - naturalLeft) / zoom}px`, '--image-panel-gap': `${62 / zoom}px`, width } as React.CSSProperties;

  const submit = async () => {
    const text = prompt.trim();
    if (!text || submitting) return;
    setSubmitting(true);
    try {
      await submitImageGeneration(text, node.id, notify);
      notify('生图任务已提交，结果将填入当前节点');
      onClose();
    } catch (error) {
      notify(error instanceof Error ? error.message : '未配置图像执行服务');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="image-edit-panel image-generate-panel nodrag nowheel" style={panelStyle} onPointerDown={(event) => event.stopPropagation()}>
      <header><div><Sparkles size={15} /><strong>生图</strong><small>根据提示词生成图片</small></div><button onClick={onClose}><X size={16} /></button></header>
      <textarea className="image-generate-prompt" aria-label="生图提示词" value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="描述你想生成的图片…" autoFocus rows={3} />
      <footer className="image-tool-footer">
        <span className={feedback ? 'has-feedback' : ''}>{feedback ?? '执行服务'}</span>
        <button className="image-tool-apply" disabled={!prompt.trim() || submitting} onClick={() => void submit()}>{submitting ? <LoaderCircle className="is-spinning" size={14} /> : '生成'} <ArrowUp size={14} /></button>
      </footer>
    </div>
  );
}
