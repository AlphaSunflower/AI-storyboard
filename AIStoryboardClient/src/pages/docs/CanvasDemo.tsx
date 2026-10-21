import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Boxes, Camera, Clapperboard, Combine, FileText, Film, Image as ImageIcon, Layers,
  Play, Sparkles, Upload, Video,
} from 'lucide-react';

/* ─────────────────────────────────────────────────────────────
 *  画布演示：中性黑白工作台（画布子站的色彩世界），
 *  演示「生产节点 → 结果槽 → 下游」与「分组才能执行」。
 * ──────────────────────────────────────────────────────────── */

type NodeId = 'script' | 'storyboard' | 'image' | 'video' | 'merge';

type CanvasNode = {
  id: NodeId;
  kind: string;
  label: string;
  sub: string;
  tone?: 'text' | 'gen';
  meta: { 类型: string; 输入: string; 动作: string; 结果槽: string; 状态: string };
  icon: ReactNode;
};

const NODES: CanvasNode[] = [
  {
    id: 'script',
    kind: 'TEXT',
    label: '剧本 · 雨夜咖啡馆',
    sub: '3 段旁白 / 420 字',
    tone: 'text',
    icon: <FileText size={15} strokeWidth={1.7} />,
    meta: { 类型: '文字节点', 输入: '无（源头）', 动作: '只承载文本', 结果槽: '不需要', 状态: '已就绪' },
  },
  {
    id: 'storyboard',
    kind: 'STORYBOARD',
    label: '分镜拆解',
    sub: '8 镜 · 每镜 12–15s',
    icon: <Clapperboard size={15} strokeWidth={1.7} />,
    meta: { 类型: '分镜生成节点', 输入: '剧本 / 文字', 动作: '拆镜 + 写提示词', 结果槽: '镜头列表（结构化）', 状态: '已生成' },
  },
  {
    id: 'image',
    kind: 'IMAGE GEN',
    label: 'AI 图片 ×4',
    sub: '4 张分镜图',
    tone: 'gen',
    icon: <ImageIcon size={15} strokeWidth={1.7} />,
    meta: { 类型: '图片生成 · text2image', 输入: 'prompt + reference', 动作: '生成 4 张', 结果槽: '4 个空占位', 状态: '待执行' },
  },
  {
    id: 'video',
    kind: 'VIDEO GEN',
    label: '生成视频 ×4',
    sub: '首尾帧 / 4–15s',
    tone: 'gen',
    icon: <Film size={15} strokeWidth={1.7} />,
    meta: { 类型: '视频生成 · firstLastFrame', 输入: 'reference 前两张 = 首帧→尾帧', 动作: '每镜出片', 结果槽: '4 个空视频占位', 状态: '待执行' },
  },
  {
    id: 'merge',
    kind: 'MERGE',
    label: '视频合并',
    sub: '按顺序拼接',
    icon: <Combine size={15} strokeWidth={1.7} />,
    meta: { 类型: '合并节点', 输入: '4 段视频（按连线顺序）', 动作: '校验片数 / 总时长', 结果槽: '成片', 状态: '等待上游' },
  },
];

const RAIL = [
  { label: '文字', icon: <FileText size={13} strokeWidth={1.7} /> },
  { label: '图片', icon: <ImageIcon size={13} strokeWidth={1.7} /> },
  { label: '视频', icon: <Video size={13} strokeWidth={1.7} /> },
  { label: '分镜拆解', icon: <Clapperboard size={13} strokeWidth={1.7} /> },
  { label: '镜头', icon: <Camera size={13} strokeWidth={1.7} /> },
  { label: '图片生成', icon: <Sparkles size={13} strokeWidth={1.7} /> },
  { label: '视频生成', icon: <Film size={13} strokeWidth={1.7} /> },
  { label: '视频合并', icon: <Combine size={13} strokeWidth={1.7} /> },
  { label: '上传', icon: <Upload size={13} strokeWidth={1.7} /> },
  { label: '项目库', icon: <Boxes size={13} strokeWidth={1.7} /> },
];

export function CanvasGraph() {
  const [active, setActive] = useState<NodeId>('image');
  const [tool, setTool] = useState('图片生成');
  const [running, setRunning] = useState<'idle' | 'running' | 'done'>('idle');
  const [step, setStep] = useState(0);
  const interval = useRef<number | null>(null);

  useEffect(() => () => {
    if (interval.current !== null) window.clearInterval(interval.current);
  }, []);

  const run = () => {
    if (running === 'running') return;
    setRunning('running');
    setStep(0);
    if (interval.current !== null) window.clearInterval(interval.current);
    let i = 0;
    interval.current = window.setInterval(() => {
      i += 1;
      setStep(i);
      if (i >= 4) {
        if (interval.current !== null) window.clearInterval(interval.current);
        interval.current = null;
        setRunning('done');
      }
    }, 420);
  };

  const current = NODES.find((n) => n.id === active) ?? NODES[0];

  return (
    <div className="mx-canvas">
      <div className="mx-canvas__top">
        <Layers size={12} strokeWidth={1.8} />
        <span className="mx-canvas__title">Morrow Canvas</span>
        <span className="mx-canvas__crumb">我的短片项目 / 画布 03</span>
        <span className="mx-canvas__save"><i />已保存 · rev 128</span>
      </div>

      <div className="mx-canvas__body">
        <div className="mx-rail" role="toolbar" aria-label="画布节点库">
          {RAIL.map((item, i) => (
            <span key={item.label} style={{ display: 'contents' }}>
              {i === 4 && <span className="mx-rail__sep" aria-hidden />}
              <button
                type="button"
                className={`mx-rail__item${tool === item.label ? ' is-active' : ''}`}
                onClick={() => setTool(item.label)}
                aria-pressed={tool === item.label}
                title={`${item.label}（演示，不真的建节点）`}
              >
                {item.icon}
                <span>{item.label}</span>
              </button>
            </span>
          ))}
        </div>

        <div className="mx-stage">
          <div className="mx-group">
            <div className="mx-group__bar">
              <span className="mx-group__name">雨夜开场 · 8 镜</span>
              <button type="button" className="mx-group__run" onClick={run}>
                <Play size={9} strokeWidth={2.6} />
                {running === 'running' ? `执行中 ${step}/4` : running === 'done' ? '已完成' : '执行'}
              </button>
              <span className="mx-group__hint">
                {running === 'idle' ? '分组才会出现执行按钮' : running === 'running' ? '按依赖顺序开跑' : '结果已落进结果槽'}
              </span>
            </div>

            <div className="mx-graph">
              {NODES.map((node, i) => (
                <span key={node.id} style={{ display: 'contents' }}>
                  {i > 0 && <span className={`mx-link${i === 2 ? ' mx-link--fan' : ''}`} aria-hidden />}
                  <button
                    type="button"
                    className={`mx-node${node.tone === 'text' ? ' mx-node--text' : ''}${active === node.id ? ' is-active' : ''}`}
                    onClick={() => setActive(node.id)}
                    aria-pressed={active === node.id}
                  >
                    <span className="mx-node__dot mx-node__dot--in" aria-hidden />
                    <span className="mx-node__dot mx-node__dot--out" aria-hidden />
                    <span className="mx-node__kind">{node.kind}</span>
                    <span className="mx-node__thumb">{node.icon}</span>
                    <span className="mx-node__label">{node.label}</span>
                    <span className="mx-node__sub">{node.sub}</span>
                  </button>
                </span>
              ))}
            </div>

            <div className="mx-row-8" style={{ marginTop: 14, gap: 4 }}>
              {['结果 1', '结果 2', '结果 3', '结果 4'].map((slot, i) => (
                <span
                  key={slot}
                  className="mx-chip mx-chip--static"
                  style={{
                    font: '8px/1.2 var(--font-code)',
                    color: running === 'done' ? 'var(--c-text)' : 'var(--c-faint)',
                    background: running === 'done' ? 'rgba(244,244,245,.1)' : 'transparent',
                    borderColor: running === 'done' ? 'rgba(244,244,245,.36)' : 'var(--c-line)',
                    borderStyle: running === 'done' || step > i ? 'solid' : 'dashed',
                    padding: '3px 7px',
                    borderRadius: 4,
                  }}
                >
                  {slot} · {step > i ? '已落图' : '空占位'}
                </span>
              ))}
            </div>
          </div>

          <div className="mx-stage__hint">
            左键框选 · 中键平移 · 双击画布空白新建 · O 整理编排 · 生产节点必须先有结果槽再连下游
          </div>
        </div>

        <div className="mx-inspector">
          <div className="mx-inspector__title">节点属性</div>
          <div className="mx-inspector__name">{current.label}</div>
          <div className="mx-inspector__meta">{current.kind.toLowerCase()} · 已选 1 个节点</div>
          {Object.entries(current.meta).map(([k, v]) => (
            <div className="mx-inspector__row" key={k}>
              <span>{k}</span>
              <b>{v}</b>
            </div>
          ))}
          <div style={{ marginTop: 10, font: '8px/1.6 var(--font-code)', color: 'var(--c-faint)' }}>
            参数（模型 / 尺寸 / 时长）走模型配置，不写进节点数据。
          </div>
        </div>
      </div>
    </div>
  );
}

/** 节点解剖：一个生产节点上到底有哪几个「口」。 */
export function NodeAnatomy() {
  return (
    <div className="mx-win mx-win--plain" style={{ boxShadow: 'none' }}>
      <div style={{ padding: 16 }}>
        <div className="mx-mini-label">一个「图片生成」节点</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <div style={{ position: 'relative', flex: '1 1 240px', border: '1px solid var(--color-hairline)', borderRadius: 10, padding: 12, background: '#fff' }}>
            <span className="mx-node__dot mx-node__dot--in" style={{ background: '#fff', borderColor: 'var(--color-primary)', left: -4 }} aria-hidden />
            <span className="mx-node__dot mx-node__dot--out" style={{ background: '#fff', borderColor: 'var(--color-primary)', right: -4 }} aria-hidden />
            <div className="mx-wf__k">imageGeneration</div>
            <div style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', marginBottom: 4 }}>AI 图片 · 雨夜招牌</div>
            <div className="mx-note">mode = text2image / fullReference / image2image / batchRefine</div>
            <div style={{ display: 'flex', gap: 4, marginTop: 10, flexWrap: 'wrap' }}>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>prompt</span>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>reference（有序）</span>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>结果槽 ×4</span>
            </div>
          </div>
          <div className="mx-note" style={{ flex: '1 1 200px', lineHeight: 1.8 }}>
            <strong style={{ color: 'var(--color-ink)' }}>左侧</strong>是输入：文字提示词，以及最多一组有序参考素材（顺序就是首帧→尾帧）。<br />
            <strong style={{ color: 'var(--color-ink)' }}>右侧</strong>是结果槽：生成结果先落进空占位，再由占位连下游。<br />
            生产节点<b>不能</b>直接连图生图 / 图生视频——少了结果槽，下游拿不到媒体。
          </div>
        </div>
      </div>
    </div>
  );
}
