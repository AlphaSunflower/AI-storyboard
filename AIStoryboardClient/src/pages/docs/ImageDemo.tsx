import { useEffect, useRef, useState } from 'react';
import {
  Image as ImageIcon, Loader, Palette, RefreshCw, Sparkles, Upload,
} from 'lucide-react';
import { Tabs } from './shared';

type ImageMode = 't2i' | 'i2i' | 'batch' | 'refine';

const MODE_PROMPT: Record<ImageMode, string> = {
  t2i: '雨夜的巷口，一位穿深绿围裙的咖啡师把热水注入滤杯，暖黄路灯与霓虹倒影，浅景深 35mm 低机位，胶片颗粒、低饱和暖调，画面右侧留白给标题，不出现文字。',
  i2i: '保持人物与构图不变，把整体色调改成清晨暖光，背景从室内换成靠窗吧台，去掉桌上的杂物。',
  batch: '同一款手冲壶的 4 张电商主图：白底、暖木台面、晨光侧逆光、45° 俯拍，不出现文字与水印。',
  refine: '人物再靠近镜头一点，虚化背景，皮肤保留真实纹理，不要磨皮。',
};

const MODE_HINT: Record<ImageMode, string> = {
  t2i: '没有参考图时就是文生图，提示词决定一切。',
  i2i: '勾选参考图后走图生图：原图是底，文字只写「改什么」。',
  batch: '多张一次提交，每张单独写提示词，别写成一句话复制 4 次。',
  refine: '生成后不满意不用重开：基于上一张继续改，改一处最稳。',
};

/** 图片生成演示：文生图 / 图生图 / 批量 / 继续完善。 */
export function ImageStudio() {
  const [mode, setMode] = useState<ImageMode>('t2i');
  const [prompt, setPrompt] = useState(MODE_PROMPT.t2i);
  const [count, setCount] = useState(4);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(true);
  const [seed, setSeed] = useState(0);
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const pick = (next: ImageMode) => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    setMode(next);
    setPrompt(MODE_PROMPT[next]);
    setDone(false);
    setBusy(false);
  };

  const generate = () => {
    if (busy) return;
    setBusy(true);
    setDone(false);
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      setBusy(false);
      setDone(true);
      setSeed((s) => s + 1);
    }, 1300);
  };

  const shown = mode === 'i2i' || mode === 'refine' ? 1 : count;
  const tones = ['', 'mx-shot--alt', 'mx-shot--night', 'mx-shot--blue'];

  return (
    <div className="mx-stack-12">
      <div className="mx-spread">
        <Tabs
          items={[
            { id: 't2i', label: '文生图' },
            { id: 'i2i', label: '图生图' },
            { id: 'batch', label: '批量' },
            { id: 'refine', label: '继续完善' },
          ]}
          value={mode}
          onChange={pick}
        />
        <span className="mx-note" style={{ fontSize: 10.5 }}>{MODE_HINT[mode]}</span>
      </div>

      <div className="mx-studio">
        <div className="mx-stack-8">
          {mode === 'i2i' && (
            <div className="mx-field">
              <div className="mx-field__label"><b>参考图</b><span>原图决定了长相与构图</span></div>
              <div
                style={{
                  display: 'flex', alignItems: 'center', gap: 10, padding: 10,
                  border: '1px dashed var(--color-hairline)', borderRadius: 9, background: 'var(--color-surface-subtle)',
                }}
              >
                <span style={{ width: 52, height: 52, borderRadius: 7, background: 'linear-gradient(150deg,#efe2d0,#c9b398)', border: '1px solid var(--color-hairline)' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ font: 'var(--text-caption)', color: 'var(--color-ink)' }}>吧台参考图.png</div>
                  <div className="mx-note" style={{ fontSize: 10 }}>换图 = 换底图；文字只写要改的部分</div>
                </div>
                <Upload size={14} strokeWidth={1.8} color="var(--color-muted)" />
              </div>
            </div>
          )}

          {mode === 'refine' && (
            <div style={{ height: 76, borderRadius: 9, border: '1px solid var(--color-hairline)', background: 'linear-gradient(150deg,#2b2b36,#171a24)', display: 'grid', placeItems: 'center', color: '#d9d5cc', font: 'var(--text-caption)' }}>
              上一张结果（作为改图源）
            </div>
          )}

          <div className="mx-field">
            <div className="mx-field__label">
              <b>{mode === 'refine' ? '改什么' : '提示词'}</b>
              <span>{prompt.length} 字</span>
            </div>
            <textarea
              className="mx-input mx-input--area"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              aria-label="图片提示词"
            />
          </div>

          <div className="mx-param-row">
            <label>
              尺寸
              <select className="mx-select" defaultValue="1024x1536">
                <option>1024x1024</option>
                <option>1024x1536</option>
                <option>1536x1024</option>
              </select>
            </label>
            <label>
              质量
              <select className="mx-select" defaultValue="高">
                <option>低</option>
                <option>中</option>
                <option>高</option>
              </select>
            </label>
          </div>

          {mode !== 'i2i' && mode !== 'refine' && (
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, font: 'var(--text-caption)', color: 'var(--color-muted)' }}>
              张数
              <input
                className="mx-range"
                type="range"
                min={1}
                max={4}
                value={count}
                onChange={(e) => setCount(Number(e.target.value))}
              />
              <b style={{ color: 'var(--color-ink)', minWidth: 14, textAlign: 'right' }}>{count}</b>
            </label>
          )}

          <div className="mx-row-8">
            <button type="button" className="mx-btn mx-btn--primary" onClick={generate} disabled={busy}>
              {busy ? <Loader size={12} strokeWidth={2.2} /> : <ImageIcon size={12} strokeWidth={2} />}
              {busy ? '生成中…' : '生成图片'}
            </button>
            <button type="button" className="mx-btn" onClick={() => setPrompt(MODE_PROMPT[mode])}>
              <RefreshCw size={12} strokeWidth={2} />
              还原示例
            </button>
          </div>

          <div className="mx-field" style={{ marginTop: 2 }}>
            <div className="mx-field__label"><b>加一句</b><span>点一下追加到提示词</span></div>
            <div className="mx-chips">
              {['浅景深', '暖光侧逆', '胶片颗粒', '右侧留白给标题', '不出现文字'].map((q) => (
                <button
                  key={q}
                  type="button"
                  className="mx-chip"
                  onClick={() => setPrompt((p) => (p.trim().endsWith('。') ? `${p}${q}。` : `${p}，${q}。`))}
                >
                  ＋ {q}
                </button>
              ))}
            </div>
            <div className="mx-note" style={{ marginTop: 2 }}>
              批量时别复制同一句：每张换一个角度或卖点，才有得挑。
            </div>
          </div>
        </div>

        <div className="mx-stack-8">
          <div className="mx-gallery">
            {done
              ? Array.from({ length: shown }).map((_, i) => (
                <div
                  key={`${seed}-${i}`}
                  className={`mx-shot ${tones[i % tones.length]}`}
                  data-label={shown > 1 ? `方案 ${String.fromCharCode(65 + i)}` : '生成结果'}
                  style={i % 3 === 2 ? { filter: 'saturate(0.86) brightness(1.05)' } : undefined}
                />
              ))
              : Array.from({ length: shown }).map((_, i) => (
                <div key={`loading-${i}`} className="mx-shot is-loading">
                  <div className="mx-shot__empty">{busy ? '生成中…' : '等待生成'}</div>
                </div>
              ))}
          </div>
          <div className="mx-result-hint">
            <Palette size={12} strokeWidth={1.9} />
            {done
              ? `已生成 ${shown} 张 · 30–60 秒/张 · 结果自动进「产出素材」`
              : busy ? '模型正在出图，可以继续在对话里说下一件事' : '点「生成图片」看一次完整出图'}
          </div>
          <div className="mx-note" style={{ lineHeight: 1.7 }}>
            出图后不满意：<strong style={{ color: 'var(--color-ink)' }}>继续完善</strong>（基于这张改一处）比重新描述整段更快，也更稳。
          </div>
        </div>
      </div>
    </div>
  );
}

/* ── 提示词解剖 ─────────────────────────────────────────── */

type SegKey = 'subject' | 'action' | 'scene' | 'camera' | 'style' | 'limit' | 'motion';

const SEG_COLOR: Record<SegKey, string> = {
  subject: '#c9805f',
  action: '#c39a4e',
  scene: '#7d9a7f',
  camera: '#6f8fa3',
  style: '#9a7d97',
  limit: '#8a857a',
  motion: '#5f8f86',
};

const SEGMENTS: { key: SegKey; label: string; text: string; note: string; why: string }[] = [
  {
    key: 'subject',
    label: '主体',
    text: '一位穿深绿围裙的咖啡师',
    note: '谁 / 什么，写到能认出来的程度：年龄、发型、服装、材质。',
    why: '主体越具体，模型越不会自由发挥。需要跨图一致时，先把人物做成资产卡。',
  },
  {
    key: 'action',
    label: '动作',
    text: '正把热水注入滤杯',
    note: '正在发生什么，用动词写，不用形容词堆。',
    why: '动作决定画面重心与手部姿态，也决定视频里「怎么动」。',
  },
  {
    key: 'scene',
    label: '环境与光线',
    text: '雨夜巷口的暖黄路灯与霓虹倒影',
    note: '时间、地点、光源方向、色温。',
    why: '同一句主体描述，换成「清晨侧光」就是完全不同的片子；光线是最便宜的风格开关。',
  },
  {
    key: 'camera',
    label: '镜头与构图',
    text: '浅景深，35mm，低机位',
    note: '景别、机位、焦段、景深；视频再补运镜。',
    why: '构图词汇能让画面有电影感，也能给标题留出位置（比如「右侧留白」）。',
  },
  {
    key: 'style',
    label: '风格与质感',
    text: '胶片颗粒，低饱和暖调',
    note: '摄影/绘画风格、色彩倾向、材质与颗粒。',
    why: '风格词放在后面，避免压过主体；不要同时写「极简」和「繁复装饰」。',
  },
  {
    key: 'limit',
    label: '约束',
    text: '画面右侧留白给标题，不出现文字',
    note: '比例、留白、禁止项、版权与品牌约束。',
    why: '负面约束要具体（「不出现文字」比「干净一点」有用）；海报一定要留白。',
  },
  {
    key: 'motion',
    label: '运镜与节奏',
    text: '镜头缓慢推进，热气在光里散开',
    note: '推 / 拉 / 摇 / 移 / 环绕，写清方向、速度和时长。',
    why: '视频模型最怕「静止描述」。一句运镜 + 一个主体动作，比十句形容词更能让画面活起来。',
  },
];

export function PromptAnatomy({ kind = 'image' }: { kind?: 'image' | 'video' }) {
  const list = kind === 'video' ? SEGMENTS : SEGMENTS.filter((s) => s.key !== 'motion');
  const [active, setActive] = useState<SegKey>('subject');
  const current = list.find((s) => s.key === active) ?? list[0];

  return (
    <div className="mx-anatomy">
      <div className="mx-mini-label">{kind === 'image' ? '一条能出好图的提示词，通常长这样' : '视频提示词：在图片六要素上再加「怎么动」'}</div>
      <p className="mx-anatomy__line">
        {list.map((seg) => (
          <button
            key={seg.key}
            type="button"
            className={`mx-seg mx-seg--${seg.key}${active === seg.key ? ' is-active' : ''}`}
            onClick={() => setActive(seg.key)}
          >
            {seg.text}
          </button>
        ))}
      </p>
      <div className="mx-anatomy__legend">
        {list.map((seg) => (
          <button
            key={seg.key}
            type="button"
            className={`mx-legend${active === seg.key ? ' is-active' : ''}`}
            onClick={() => setActive(seg.key)}
          >
            <i style={{ background: SEG_COLOR[seg.key] }} />
            <span>{seg.label}</span>
          </button>
        ))}
      </div>
      <div className="mx-anatomy__note">
        <div style={{ color: 'var(--color-ink)', fontWeight: 600, marginBottom: 3 }}>{current.label} · {current.note}</div>
        <div>{current.why}</div>
      </div>
      <div className="mx-row-8" style={{ marginTop: 10 }}>
        <Sparkles size={12} strokeWidth={1.9} color="var(--color-primary)" />
        <span className="mx-note">
          别一次改六段：先定主体与场景，再调风格；一次只改一处，才知道是哪句起了作用。
        </span>
      </div>
    </div>
  );
}
