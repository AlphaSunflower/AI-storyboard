import { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle, Check, Clapperboard, Combine, Loader, Play,
} from 'lucide-react';

type Clip = {
  id: number;
  title: string;
  seconds: number;
  imagePrompt: string;
  videoPrompt: string;
  missing?: boolean;
};

const BASE_CLIPS: Clip[] = [
  { id: 1, title: '雨夜招牌', seconds: 15, imagePrompt: '雨夜街角，咖啡馆招牌刚亮起，湿漉漉的路面反射暖光。', videoPrompt: '镜头从街对面缓慢横移到门口，招牌灯一格格亮起。' },
  { id: 2, title: '推门进店', seconds: 15, imagePrompt: '手推开木门，室内暖光泄到台阶上，门框形成天然画框。', videoPrompt: '跟随手部向前推进，暖光逐渐铺满画面。' },
  { id: 3, title: '吧台手冲', seconds: 14, imagePrompt: '咖啡师在暖木吧台前注水，蒸汽在晨光里升起。', videoPrompt: '中景缓慢推近到滤杯，蒸汽向上飘散。' },
  { id: 4, title: '蒸汽特写', seconds: 12, imagePrompt: '滤杯上方蒸汽与光斑，背景虚化成暖色块面。', videoPrompt: '固定机位，浅景深，蒸汽与光斑缓慢流动。' },
  { id: 5, title: '拉花入杯', seconds: 12, imagePrompt: '奶泡落入杯中形成叶片拉花，俯拍 45°。', videoPrompt: '俯拍固定，手腕轻微转动，奶泡纹路展开。' },
  { id: 6, title: '窗边坐下', seconds: 14, imagePrompt: '客人端杯坐到窗边，雨后街道在玻璃上反光。', videoPrompt: '镜头后拉，把人物与环境一起收进画面。' },
  { id: 7, title: '举杯望向窗外', seconds: 14, imagePrompt: '侧逆光，杯口热气，人物望向窗外，表情放松。', videoPrompt: '侧向缓慢环绕 15°，热气在逆光里发亮。' },
  { id: 8, title: '片尾收灯', seconds: 14, imagePrompt: '店铺打烊，招牌熄灭，只剩街灯与雨。', videoPrompt: '固定镜头，招牌灯逐个熄灭，画面渐暗。' },
];

const fmt = (s: number) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;

/** 长视频时间线：点击镜头看提示词；切「缺两段」看合并为什么会被拦下。 */
export function TimelineComposer() {
  const [missing, setMissing] = useState(false);
  const [selected, setSelected] = useState(1);
  const [merge, setMerge] = useState<'idle' | 'merging' | 'merged'>('idle');
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const clips = BASE_CLIPS.map((c) => ({ ...c, missing: missing && c.id >= 7 }));
  const doneCount = clips.filter((c) => !c.missing).length;
  const total = clips.filter((c) => !c.missing).reduce((sum, c) => sum + c.seconds, 0);
  const fullTotal = BASE_CLIPS.reduce((sum, c) => sum + c.seconds, 0);
  const active = clips.find((c) => c.id === selected) ?? clips[0];
  const blocked = doneCount < clips.length;

  const toggleMissing = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    setMissing((m) => !m);
    setMerge('idle');
  };

  const doMerge = () => {
    if (blocked || merge === 'merging') return;
    setMerge('merging');
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setMerge('merged'), 1400);
  };

  return (
    <div className="mx-timeline">
      <div className="mx-spread" style={{ marginBottom: 12 }}>
        <div className="mx-row-8">
          <span className="mx-chip mx-chip--static" style={{ fontSize: 10.5 }}>
            <Clapperboard size={10} strokeWidth={2} style={{ verticalAlign: -1, marginRight: 4 }} />
            分镜草稿 · 8 镜
          </span>
          <span className="mx-note" style={{ fontSize: 10.5 }}>每镜 12–15 秒 · 首尾帧自动衔接</span>
        </div>
        <button type="button" className="mx-btn" onClick={toggleMissing}>
          {missing ? '模拟：全部生成完' : '模拟：还缺 2 段'}
        </button>
      </div>

      <div className="mx-timeline__ruler" aria-hidden>
        <span>00:00</span>
        <span>00:30</span>
        <span>01:00</span>
        <span>01:30</span>
        <span>{fmt(fullTotal)}</span>
      </div>

      <div className="mx-timeline__track" role="list">
        {clips.map((c) => (
          <button
            key={c.id}
            type="button"
            role="listitem"
            className={`mx-clip${selected === c.id ? ' is-active' : ''}${c.missing ? ' is-missing' : ''}`}
            onClick={() => setSelected(c.id)}
            aria-label={`镜头 ${c.id} ${c.title}${c.missing ? '（缺片）' : ''}`}
          >
            <div className="mx-clip__thumb" />
            <div className="mx-clip__n">{String(c.id).padStart(2, '0')} · {c.seconds}s</div>
            <div className="mx-clip__t">{c.missing ? '缺片' : c.title}</div>
          </button>
        ))}
      </div>

      <div className="mx-timeline__foot">
        <div className="mx-timeline__total">
          <span>已生成 <b>{doneCount}</b> / 8 段</span>
          <span aria-hidden>·</span>
          <span>成片 <b>{fmt(total)}</b></span>
        </div>
        <div className="mx-row-8">
          <button type="button" className="mx-btn mx-btn--primary" onClick={doMerge} disabled={blocked || merge === 'merging'}>
            {merge === 'merging' ? <Loader size={12} strokeWidth={2.2} /> : merge === 'merged' ? <Check size={12} strokeWidth={2.2} /> : <Combine size={12} strokeWidth={2} />}
            {merge === 'merging' ? '合并中…' : merge === 'merged' ? '已合并' : '合并成片'}
          </button>
          <button type="button" className="mx-btn" onClick={() => setMerge('idle')} disabled={merge !== 'merged'}>
            在播放器里预览
          </button>
        </div>
      </div>

      <div className={`mx-merge-bar${blocked ? ' is-blocked' : ''}`}>
        {blocked ? (
          <>
            <AlertTriangle size={13} strokeWidth={2} color="var(--color-warning)" />
            <span>还缺 2 段：合并会校验片数与总时长，缺片不会静默跳过，也不会伪造成片。</span>
          </>
        ) : merge === 'merged' ? (
          <>
            <Check size={13} strokeWidth={2.2} color="var(--color-success)" />
            <span>已按 01 → 08 顺序拼接，输出 {fmt(fullTotal)} · 1080P。合并前会逐段校验，缺一段就停。</span>
          </>
        ) : (
          <>
            <Play size={13} strokeWidth={2} color="var(--color-primary)" />
            <span>全部镜头就位，可以合并。合并是最后一步，不在生成过程中做。</span>
          </>
        )}
      </div>

      <div
        style={{
          marginTop: 14, padding: 14, borderRadius: 10,
          border: '1px solid var(--color-hairline)', background: 'var(--color-surface-subtle)',
        }}
      >
        <div className="mx-spread" style={{ marginBottom: 8 }}>
          <div style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)' }}>
            镜头 {String(active.id).padStart(2, '0')} · {active.title}
            <span className="mx-chip mx-chip--static" style={{ marginLeft: 8, fontSize: 10 }}>{active.seconds}s</span>
          </div>
          <span className="mx-note" style={{ fontSize: 10 }}>
            {active.id > 1 ? `首帧取自镜头 ${String(active.id - 1).padStart(2, '0')} 的尾帧` : '开场镜头 · 无前置衔接'}
          </span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(220px,1fr))', gap: 16 }}>
          <div>
            <div className="mx-field__label" style={{ justifyContent: 'flex-start', gap: 6 }}><b>图片提示词</b><span>· 先出静帧</span></div>
            <div className="mx-note" style={{ color: 'var(--color-body)', lineHeight: 1.7 }}>{active.imagePrompt}</div>
          </div>
          <div>
            <div className="mx-field__label" style={{ justifyContent: 'flex-start', gap: 6 }}><b>视频提示词</b><span>· 再让它动起来</span></div>
            <div className="mx-note" style={{ color: 'var(--color-body)', lineHeight: 1.7 }}>{active.videoPrompt}</div>
          </div>
        </div>
      </div>
    </div>
  );
}
