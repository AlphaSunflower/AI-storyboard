import { memo, useEffect, useMemo, useState } from 'react';
import { Clapperboard, LayoutGrid, LoaderCircle } from 'lucide-react';
import type { CanvasNode } from '../../types';
import { submitStoryboardGeneration } from '../../services/jobRunner';
import { useCanvasStore } from '../../store/canvasStore';
import { buildReferenceImagePool, type ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { RichPromptInput } from '../RichPromptInput';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';
import { NodeFloatPanel, NodeSurface, NodeSurfaceEmpty } from './nodePanel';

type Frames = Array<{ img?: string; label?: string; description?: string; imageUrl?: string; duration?: number }>;
const STYLE_LABELS: Record<string, string> = { '2d-anime': '2D动漫', '3d-cg': '3D国漫', 'live-action': '真人实拍' };

// 分镜生成节点的卡片内容（无 Handle——当前由 MediaNode 按 data.kind 分发渲染）。
// 后续若切回"每类型独立节点组件"，此组件可直接复用。
export const StoryboardGenView = memo(function StoryboardGenView({ node, updateNode }: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
}) {
  const { id, data } = node;
  const [generating, setGenerating] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const draftText = useNodeTextDraft({
    nodeId: `${id}:storyboard-gen`,
    value: String(data.scriptText ?? data.prompt ?? ''),
    onChange: (value) => updateNode(id, { scriptText: value, prompt: value }),
  });
  const frames = (data.frames as Frames | undefined) ?? [];
  const rows = data.rows ?? 2;
  const cols = data.cols ?? 3;
  const styleType = data.styleType ?? '2d-anime';

  // 参考图池（对齐参考 FrameDescriptionCell：每帧描述可 @引用上游素材）
  const nodes = useCanvasStore((state) => state.nodes);
  const edges = useCanvasStore((state) => state.edges);
  const referencePool = useMemo(() => buildReferenceImagePool(id, nodes, edges, 1), [id, nodes, edges]);

  // 更新某帧的分镜描述
  const updateFrameDescription = (index: number, description: string) => {
    const next = [...frames];
    next[index] = { ...next[index], description };
    updateNode(id, { frames: next });
  };

  const submit = () => {
    const text = draftText.value.trim();
    if (!text) { setFeedback('请先输入剧本或画面提示词'); window.setTimeout(() => setFeedback(null), 2200); return; }
    setGenerating(true);
    void submitStoryboardGeneration(text, id, (msg) => setFeedback(msg), { rows, cols, styleType })
      .then(() => setFeedback('分镜生成任务已提交'))
      .catch((error) => setFeedback(error instanceof Error ? error.message : '分镜生成提交失败'))
      .finally(() => setGenerating(false));
  };

  return (
    <div className="storyboard-gen-view" data-generated={frames.length ? 'true' : 'false'}>
      <NodeSurface state={frames.length ? 'ready' : 'empty'} className="storyboard-gen-surface">
        {frames.length > 0 ? (
          <div className="storyboard-grid storyboard-grid--gen" style={{ '--gen-cols': cols, '--gen-rows': rows } as React.CSSProperties}>
            {frames.map((frame, i) => {
              const imgSrc = frame.img || frame.imageUrl;
              const desc = frame.description ?? '';
              return (
                <figure key={i} className="storyboard-frame" style={{ '--gen-cols': cols, '--gen-rows': rows } as React.CSSProperties}>
                  <div className="storyboard-frame__thumb">
                    {imgSrc ? <img src={imgSrc} alt={`镜头${i + 1}`} /> : (
                      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="rgba(238,240,223,.4)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" opacity="0.5">
                        <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>
                      </svg>
                    )}
                  </div>
                  <figcaption className="storyboard-frame__cap">
                    <span className="storyboard-frame__no">#{String(i + 1).padStart(2, '0')}</span>
                    <span className="storyboard-frame__style">{STYLE_LABELS[styleType] ?? styleType}</span>
                  </figcaption>
                  <RichPromptInput
                    className="storyboard-frame__desc"
                    value={desc}
                    onChange={(v) => updateFrameDescription(i, v)}
                    placeholder="分镜描述…"
                    pool={referencePool}
                    showStrip={false}
                    minHeight={36}
                    maxHeight={0}
                  />
                  <figcaption className="storyboard-frame__label">{frame.label ?? `镜头${i + 1}`}</figcaption>
                </figure>
              );
            })}
          </div>
        ) : (
          <NodeSurfaceEmpty icon={<LayoutGrid size={30} />} label="分镜生成节点" hint="输入剧本后生成分镜帧网格" />
        )}
      </NodeSurface>
      <NodeFloatPanel nodeId={id} className="storyboard-gen-panel">
        <textarea className="text-node-editor nodrag nowheel" aria-label="分镜生成输入"
          value={draftText.value} placeholder="在此粘贴剧本或画面提示词…"
          onChange={(event) => draftText.setValue(event.target.value)}
          onFocus={draftText.onFocus}
          onBlur={draftText.onBlur}
          onCompositionStart={draftText.onCompositionStart}
          onCompositionEnd={draftText.onCompositionEnd} />
        <div className="storyboard-gen-config nodrag nowheel">
          <label className="storyboard-gen-field"><span>行</span>
            <input type="number" min={1} max={6} value={rows} onChange={(e) => updateNode(id, { rows: Number(e.target.value) || 1 })} />
          </label>
          <label className="storyboard-gen-field"><span>列</span>
            <input type="number" min={1} max={6} value={cols} onChange={(e) => updateNode(id, { cols: Number(e.target.value) || 1 })} />
          </label>
          <label className="storyboard-gen-field"><span>风格</span>
            <select value={styleType} onChange={(e) => updateNode(id, { styleType: e.target.value as CanvasNode['data']['styleType'] })}>
              {Object.entries(STYLE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
        </div>
        <button className="node-upload node-upload--generate nodrag" disabled={generating} onClick={submit}>
          {generating ? <LoaderCircle className="is-spinning" size={14} /> : <Clapperboard size={14} />} {generating ? '生成中' : '生成分镜'}
        </button>
        {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      </NodeFloatPanel>
    </div>
  );
});
