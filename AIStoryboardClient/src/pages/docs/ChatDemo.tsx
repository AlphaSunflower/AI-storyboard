import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Check, Clapperboard, FileText, Frame, Image as ImageIcon, Layers, Loader, Presentation,
  Send, Sparkles, Video,
} from 'lucide-react';
import { Win, FileCard } from './shared';

/* ─────────────────────────────────────────────────────────────
 *  Moon 对话演示：选一个目标 → 看它怎么回、怎么要确认、怎么交付。
 *  纯本地脚本演示，不调用任何接口。
 * ──────────────────────────────────────────────────────────── */

type Product = 'image' | 'long' | 'canvas' | 'ppt';

type Scenario = {
  id: Product;
  chip: string;
  icon: ReactNode;
  ask: string;
  reply: string;
  plan: { title: string; rows: [string, string][] };
  confirmLabel: string;
  result: ReactNode;
  resultText: string;
};

const SCENARIOS: Scenario[] = [
  {
    id: 'image',
    chip: '一张海报',
    icon: <ImageIcon size={13} strokeWidth={1.9} />,
    ask: '做一张雨夜咖啡馆的开业海报，竖版 9:16，电影感，留出标题位置。',
    reply: '我按「竖版海报 + 电影感」来写提示词，先定尺寸与张数。',
    plan: {
      title: '图片生成参数（已按推荐选择）',
      rows: [
        ['模型', 'gpt-image-2'],
        ['尺寸', '1024×1536（9:16）'],
        ['质量 / 张数', '高 / 2 张'],
        ['提示词要点', '雨夜、暖光、留白标题区'],
      ],
    },
    confirmLabel: '确认生成',
    resultText: '2 张图已生成，已放进「产出素材」，点开可放大、可继续完善。',
    result: (
      <div className="mx-gallery" style={{ marginTop: 8 }}>
        <div className="mx-shot mx-shot--night" data-label="方案 A" />
        <div className="mx-shot mx-shot--night" data-label="方案 B" style={{ filter: 'hue-rotate(-12deg) brightness(1.08)' }} />
      </div>
    ),
  },
  {
    id: 'long',
    chip: '40 秒短片',
    icon: <Clapperboard size={13} strokeWidth={1.9} />,
    ask: '把这段 40 秒的旁白做成一条完整的咖啡店短片。',
    reply: '40 秒超过单段 15 秒上限，我走分镜流程：先出镜头大纲，你确认后逐镜生成再合并。',
    plan: {
      title: '分镜方案（阶段 A · 镜头大纲）',
      rows: [
        ['镜头数', '4 个（每镜 10 秒）'],
        ['镜头 1', '雨夜街口，招牌亮起'],
        ['镜头 2', '推门进店，暖光扑面'],
        ['镜头 3', '手冲特写，蒸汽升起'],
        ['镜头 4', '坐下举杯，看向窗外'],
      ],
    },
    confirmLabel: '大纲可以，继续',
    resultText: '4 镜已逐镜生成并合并为 00:40，首尾帧已自动衔接，缺片时不会伪造成片。',
    result: (
      <div style={{ marginTop: 8 }}>
        <div className="mx-video-frame" style={{ aspectRatio: '21 / 9' }}>
          <span className="mx-video-frame__play"><Check size={18} strokeWidth={2.4} /></span>
          <span className="mx-video-frame__cap">咖啡店 40s · 1080P · 16:9</span>
        </div>
        <div className="mx-filmstrip" aria-hidden>
          {Array.from({ length: 8 }).map((_, i) => <i key={i} className="is-on" />)}
        </div>
      </div>
    ),
  },
  {
    id: 'canvas',
    chip: '搭一条画布流程',
    icon: <Frame size={13} strokeWidth={1.9} />,
    ask: '在画布上搭一条流程：剧本拆 4 个分镜，每镜生一张图，再生成一段转场视频，最后合并。',
    reply: '我直接在当前画布建图，一次提交节点、连线、结果槽和布局，不逐条执行。',
    plan: {
      title: '画布变更预览',
      rows: [
        ['新建节点', '剧本 1 · 分镜 1 · 图片生成 4 · 视频生成 1 · 视频合并 1'],
        ['结果槽', '图片 4 个、视频 5 个（自动连回生产节点）'],
        ['分组', '「雨夜开场」（5 个生成节点）'],
        ['执行方式', '分组标题上的「执行」由你点'],
      ],
    },
    confirmLabel: '就这样搭',
    resultText: '已搭好并分组。点分组「雨夜开场」标题上的「执行」，才会按依赖顺序真的开跑。',
    result: (
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
        {['剧本', '分镜', '图片生成 ×4', '视频生成', '视频合并'].map((n, i) => (
          <span
            key={n}
            className="mx-chip mx-chip--static"
            style={{ fontSize: 10.5, padding: '3px 8px', borderColor: i === 4 ? 'var(--color-ink)' : undefined, color: i === 4 ? 'var(--color-ink)' : undefined }}
          >
            {n}
          </span>
        ))}
      </div>
    ),
  },
  {
    id: 'ppt',
    chip: '一份 PPT',
    icon: <Presentation size={13} strokeWidth={1.9} />,
    ask: '做一份 12 页的产品发布会 PPT，深色高级感，数据页要图表。',
    reply: '我用 ppt-master 生成可编辑的 PPTX，先跟你确认画布、页数、配色和交付格式。',
    plan: {
      title: '设计参数确认',
      rows: [
        ['画布', '1280×720（16:9）· 正文 30px · 12 页'],
        ['配色', '底 #141413 / 面板 #1F1E1B / 强调 #CC785C'],
        ['字体', '标题与正文同族；数字等宽'],
        ['交付', 'output/发布会.pptx + preview/page-01.png'],
      ],
    },
    confirmLabel: '开始生成',
    resultText: '已交付可编辑 PPTX 与每页预览图，聊天里可直接下载，也能在资产库预览。',
    result: (
      <div className="mx-filecards">
        <FileCard icon={<Presentation size={14} strokeWidth={1.9} />} name="发布会.pptx" meta="12 页 · 8.4 MB · 可编辑" />
        <FileCard icon={<ImageIcon size={14} strokeWidth={1.9} />} name="preview/page-01.png" meta="预览图 · 1280×720" />
      </div>
    ),
  },
];

export function ChatDemo() {
  const [scenario, setScenario] = useState<Scenario>(SCENARIOS[0]);
  const [stage, setStage] = useState<'idle' | 'asked' | 'plan' | 'working' | 'done'>('idle');
  const [input, setInput] = useState('');
  const [progress, setProgress] = useState(0);
  const timers = useRef<number[]>([]);
  const intervals = useRef<number[]>([]);

  useEffect(() => () => {
    timers.current.forEach((id) => window.clearTimeout(id));
    intervals.current.forEach((id) => window.clearInterval(id));
  }, []);

  const later = (fn: () => void, ms: number) => {
    const id = window.setTimeout(fn, ms);
    timers.current.push(id);
  };

  const reset = (keepInput = false) => {
    timers.current.forEach((id) => window.clearTimeout(id));
    intervals.current.forEach((id) => window.clearInterval(id));
    timers.current = [];
    intervals.current = [];
    setStage('idle');
    setProgress(0);
    setInput(keepInput ? scenario.ask : '');
  };

  const send = () => {
    const text = input.trim();
    if (!text || stage !== 'idle') return;
    const matched = SCENARIOS.find((s) => text === s.ask) ?? scenario;
    setScenario(matched);
    setStage('asked');
    later(() => setStage('plan'), 700);
  };

  const chooseScenario = (s: Scenario) => {
    if (stage === 'working') return;
    reset();
    setScenario(s);
    setInput(s.ask);
  };

  const confirm = () => {
    if (stage !== 'plan') return;
    setStage('working');
    setProgress(12);
    const tick = window.setInterval(() => {
      setProgress((p) => {
        if (p >= 92) {
          window.clearInterval(tick);
          return 96;
        }
        return p + 14;
      });
    }, 160);
    timers.current.push(tick);
    intervals.current.push(tick);
    later(() => {
      window.clearInterval(tick);
      setProgress(100);
      setStage('done');
    }, 1500);
  };

  const stageReady = stage === 'idle';

  return (
    <div className="mx-chat">
      <div className="mx-chat__head">
        <span className="mx-chat__avatar" aria-hidden>
          <Sparkles size={13} strokeWidth={1.9} />
        </span>
        <span className="mx-chat__name">Moon · 新对话</span>
        <span className="mx-chip mx-chip--static" style={{ marginLeft: 'auto', fontSize: 10 }}>
          方案批准
        </span>
      </div>

      <div className="mx-chat__stage">
        <div className="mx-msg mx-msg--ai">
          说目标就行：做图、做片、搭画布、写文档、做 PPT。我会自己选技能，必要时先给你方案。
        </div>

        {stage !== 'idle' && (
          <div className="mx-msg mx-msg--user">{scenario.ask}</div>
        )}

        {stage === 'asked' && (
          <div className="mx-msg--typing" aria-label="Moon 正在回复">
            <i /><i /><i />
          </div>
        )}

        {(stage === 'plan' || stage === 'working' || stage === 'done') && (
          <div className="mx-msg mx-msg--ai">
            {scenario.reply}
            <div className="mx-msg__meta">
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>{scenario.chip}</span>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>
                {scenario.id === 'canvas' ? 'canvas-design' : scenario.id === 'ppt' ? 'ppt-master' : scenario.id === 'image' ? 'image' : 'storyboard'}
              </span>
            </div>
          </div>
        )}

        {(stage === 'plan' || stage === 'working' || stage === 'done') && (
          <div className="mx-plan">
            <div className="mx-plan__title">
              <Layers size={13} strokeWidth={1.9} />
              {scenario.plan.title}
            </div>
            {scenario.plan.rows.map(([k, v]) => (
              <div className="mx-plan__row" key={k}>
                <span>{k}</span>
                <b>{v}</b>
              </div>
            ))}
            <div className="mx-plan__actions">
              <button
                type="button"
                className="mx-btn mx-btn--primary"
                onClick={confirm}
                disabled={stage !== 'plan'}
              >
                {stage === 'plan' ? <Check size={12} strokeWidth={2.2} /> : stage === 'working' ? <Loader size={12} strokeWidth={2.2} /> : <Check size={12} strokeWidth={2.2} />}
                {stage === 'plan' ? scenario.confirmLabel : stage === 'working' ? `执行中 ${progress}%` : '已完成'}
              </button>
              <button
                type="button"
                className="mx-btn"
                onClick={() => reset(true)}
                disabled={stage !== 'plan'}
              >
                改一下
              </button>
              {stage === 'done' && (
                <button type="button" className="mx-btn mx-btn--ghost" onClick={() => reset()}>
                  再试一个
                </button>
              )}
            </div>
            {(stage === 'working' || stage === 'done') && (
              <div style={{ marginTop: 10 }}>
                <div className="mx-filmstrip" aria-hidden>
                  {Array.from({ length: 10 }).map((_, i) => (
                    <i key={i} className={i < Math.round(progress / 10) ? 'is-on' : ''} />
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {stage === 'done' && (
          <div className="mx-msg mx-msg--ai" style={{ maxWidth: '100%' }}>
            {scenario.resultText}
            {scenario.result}
          </div>
        )}
      </div>

      <div className="mx-chips">
        {SCENARIOS.map((s) => (
          <button
            key={s.id}
            type="button"
            className={`mx-chip${scenario.id === s.id && stage !== 'idle' ? ' is-active' : ''}`}
            onClick={() => chooseScenario(s)}
          >
            {s.icon}
            <span style={{ marginLeft: 5 }}>{s.chip}</span>
          </button>
        ))}
      </div>

      <div className="mx-composer">
        <textarea
          className="mx-composer__input"
          rows={1}
          value={input}
          readOnly={!stageReady}
          placeholder={stageReady ? '描述你的目标，Enter 发送（这是演示，不会真的生成）' : '演示进行中…'}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          aria-label="演示用输入框"
        />
        <button type="button" className="mx-composer__send" onClick={send} disabled={!stageReady} aria-label="发送">
          <Send size={14} strokeWidth={2} />
        </button>
      </div>
      <div className="mx-note" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Video size={11} strokeWidth={1.9} />
        视频走异步：返回任务号就结束这一轮，生成完自动把结果补进对话。
        <FileText size={11} strokeWidth={1.9} style={{ marginLeft: 6 }} />
        文档与 PPT 都落在「产出素材」。
      </div>
    </div>
  );
}

export function ChatDemoWindow() {
  return (
    <Win title={<>Moon 工作台 · 可点演示</>} meta="DEMO · 不消耗额度">
      <ChatDemo />
    </Win>
  );
}
