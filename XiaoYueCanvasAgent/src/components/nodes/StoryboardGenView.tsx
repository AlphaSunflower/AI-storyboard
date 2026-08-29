import { memo, useState } from 'react';
import { Clapperboard, LayoutGrid, LoaderCircle } from 'lucide-react';
import type { CanvasNode } from '../../types';
import { submitStoryboardGeneration } from '../../services/jobRunner';

type Frames = Array<{ img?: string; label?: string; description?: string }>;
const STYLE_LABELS: Record<string, string> = { '2d-anime': '2D动漫', '3d-cg': '3D国漫', 'live-action': '真人实拍' };

// 分镜生成节点的卡片内容（无 Handle——当前由 MediaNode 按 data.kind 分发渲染）。
// 后续若切回"每类型独立节点组件"，此组件可直接复用。
export const StoryboardGenView = memo(function StoryboardGenView({ node, updateNode }: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
}) {
  const { id, data } = node;
  const [draftText, setDraftText] = useState('');
  const [generating, setGenerating] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const frames = (data.frames as Frames | undefined) ?? [];
  const rows = data.rows ?? 2;
  const cols = data.cols ?? 3;
  const styleType = data.styleType ?? '2d-anime';

  const submit = () => {
    const text = draftText.trim();
    if (!text) { setFeedback('请先输入剧本或画面提示词'); window.setTimeout(() => setFeedback(null), 2200); return; }
    setGenerating(true);
    void submitStoryboardGeneration(text, id, (msg) => setFeedback(msg), { rows, cols, styleType })
      .then(() => setFeedback('分镜生成任务已提交'))
      .catch((error) => setFeedback(error instanceof Error ? error.message : '分镜生成提交失败'))
      .finally(() => setGenerating(false));
  };

  return (
    <div className="storyboard-gen-view" data-generated={frames.length ? 'true' : 'false'}>
      <textarea className="text-node-editor nodrag nowheel" aria-label="分镜生成输入"
        value={draftText} placeholder="在此粘贴剧本或画面提示词…"
        onChange={(e) => setDraftText(e.target.value)} />
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
      {frames.length > 0 ? (
        <div className="storyboard-grid storyboard-grid--gen" style={{ '--gen-cols': cols, '--gen-rows': rows } as React.CSSProperties}>
          {frames.map((frame, i) => (
            <figure key={i} className="storyboard-frame" style={{ '--gen-cols': cols, '--gen-rows': rows } as React.CSSProperties}>
              {frame.img && <img src={frame.img} alt="" />}
              <figcaption>{frame.label ?? `镜头${i + 1}`}</figcaption>
            </figure>
          ))}
        </div>
      ) : (
        <div className="empty-image-node"><LayoutGrid size={26} /><strong>分镜生成节点</strong><span>输入剧本后生成分镜帧网格</span></div>
      )}
    </div>
  );
});
