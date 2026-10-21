import { useEffect, useRef, useState } from 'react';
import {
  Clock, Film, Loader, Play, Video,
} from 'lucide-react';
import { Tabs } from './shared';

type VideoMode = 'text' | 'image' | 'firstlast' | 'videoRef';

const MODE_COPY: Record<VideoMode, { label: string; prompt: string; note: string }> = {
  text: {
    label: '文生视频',
    prompt: '雨夜巷口，穿深绿围裙的咖啡师把热水注入滤杯；镜头缓慢推近，热气在暖光里散开，雨滴从檐角落下。',
    note: '没有参考素材，纯靠提示词：主体 + 动作 + 运镜 + 光线。',
  },
  image: {
    label: '图生视频',
    prompt: '以这张图为第一帧：人物保持不动，镜头轻微向前推进，霓虹倒影在水面轻轻晃动。',
    note: '一张参考图默认作为首帧，画面长相由这张图锁住。',
  },
  firstlast: {
    label: '首尾帧',
    prompt: '从「推门进店」过渡到「坐下举杯」：镜头跟随人物前移，暖光逐渐铺满画面。',
    note: '两张参考图按顺序作为首帧 → 尾帧，中间由模型补运动。',
  },
  videoRef: {
    label: '参考视频',
    prompt: '参考这段视频的运镜节奏与色调，把主体换成咖啡师，场景换成雨夜咖啡馆。',
    note: '参考视频学的是运动与风格，不是把原片重播一遍。',
  },
};

export function VideoStudio() {
  const [mode, setMode] = useState<VideoMode>('text');
  const [prompt, setPrompt] = useState(MODE_COPY.text.prompt);
  const [duration, setDuration] = useState(8);
  const [ratio, setRatio] = useState('16:9');
  const [status, setStatus] = useState<'idle' | 'running' | 'done'>('idle');
  const [progress, setProgress] = useState(0);
  const timer = useRef<number | null>(null);
  const interval = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    if (interval.current !== null) window.clearInterval(interval.current);
  }, []);

  const stop = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    if (interval.current !== null) window.clearInterval(interval.current);
    timer.current = null;
    interval.current = null;
  };

  const pick = (next: VideoMode) => {
    stop();
    setMode(next);
    setPrompt(MODE_COPY[next].prompt);
    setStatus('idle');
    setProgress(0);
  };

  const generate = () => {
    if (status === 'running') return;
    stop();
    setStatus('running');
    setProgress(6);
    interval.current = window.setInterval(() => {
      setProgress((p) => (p >= 92 ? 94 : p + 7));
    }, 150);
    timer.current = window.setTimeout(() => {
      stop();
      setProgress(100);
      setStatus('done');
    }, 1900);
  };

  const frames = Math.max(2, Math.round(duration / 2));

  return (
    <div className="mx-stack-12">
      <div className="mx-spread">
        <Tabs
          items={(Object.keys(MODE_COPY) as VideoMode[]).map((id) => ({ id, label: MODE_COPY[id].label }))}
          value={mode}
          onChange={pick}
        />
        <span className="mx-note" style={{ fontSize: 10.5 }}>{MODE_COPY[mode].note}</span>
      </div>

      <div className="mx-studio">
        <div className="mx-stack-8">
          {mode !== 'text' && (
            <div className="mx-field">
              <div className="mx-field__label"><b>参考素材</b><span>{mode === 'firstlast' ? '首帧 → 尾帧，顺序即结果' : '一张图 / 一段视频'}</span></div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {[0, 1].slice(0, mode === 'firstlast' ? 2 : 1).map((i) => (
                  <span
                    key={i}
                    style={{
                      width: 58, height: 40, borderRadius: 7,
                      background: i === 0 ? 'linear-gradient(150deg,#efe2d0,#c9b398)' : 'linear-gradient(150deg,#2b2b36,#171a24)',
                      border: '1px solid var(--color-hairline)',
                    }}
                  />
                ))}
                {mode === 'firstlast' && <span style={{ color: 'var(--color-muted-soft)', fontSize: 12 }}>→</span>}
                <span className="mx-note" style={{ fontSize: 10 }}>
                  {mode === 'firstlast' ? '首帧 / 尾帧' : mode === 'videoRef' ? '参考片段 00:04' : '首帧'}
                </span>
              </div>
            </div>
          )}

          <div className="mx-field">
            <div className="mx-field__label"><b>视频提示词</b><span>{duration}s · {ratio}</span></div>
            <textarea
              className="mx-input mx-input--area"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              aria-label="视频提示词"
            />
          </div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 8, font: 'var(--text-caption)', color: 'var(--color-muted)' }}>
            时长
            <input
              className="mx-range"
              type="range"
              min={4}
              max={15}
              value={duration}
              onChange={(e) => setDuration(Number(e.target.value))}
            />
            <b style={{ color: 'var(--color-ink)', minWidth: 30, textAlign: 'right' }}>{duration}s</b>
          </label>

          <div className="mx-row-8">
            {['16:9', '9:16', '1:1'].map((r) => (
              <button
                key={r}
                type="button"
                className={`mx-chip${ratio === r ? ' is-active' : ''}`}
                onClick={() => setRatio(r)}
              >
                {r}
              </button>
            ))}
            <select className="mx-select" defaultValue="1080P" aria-label="分辨率">
              <option>1080P</option>
              <option>768P</option>
              <option>720P</option>
            </select>
          </div>

          <button type="button" className="mx-btn mx-btn--primary" onClick={generate} disabled={status === 'running'} style={{ alignSelf: 'flex-start' }}>
            {status === 'running' ? <Loader size={12} strokeWidth={2.2} /> : <Video size={12} strokeWidth={2} />}
            {status === 'running' ? `生成中 ${progress}%` : status === 'done' ? '再生成一条' : '生成视频'}
          </button>

          <div className="mx-note" style={{ lineHeight: 1.7 }}>
            <Clock size={11} strokeWidth={1.9} style={{ verticalAlign: -1, marginRight: 4 }} />
            单段最长 15 秒，通常 2–5 分钟出片。任务提交后这一轮就结束了，结果会自动补进对话。
          </div>

          <div>
            <div className="mx-field__label" style={{ marginBottom: 6, justifyContent: 'flex-start', gap: 6 }}>
              <b>这三句一定要写</b>
              <span>· 少了就会「像一张会呼吸的照片」</span>
            </div>
            <div className="mx-stack-8">
              {[
                ['主体动作', '谁 / 什么在动，动向哪里'],
                ['运镜方向', '推、拉、摇、移、环绕；加一个「缓慢」'],
                ['光线变化', '光从哪来、怎么变，决定情绪'],
              ].map(([t, d]) => (
                <div key={t} style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
                  <span className="mx-chip mx-chip--static" style={{ fontSize: 10, flexShrink: 0 }}>{t}</span>
                  <span className="mx-note" style={{ fontSize: 10.5 }}>{d}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="mx-stack-8">
          <div
            className="mx-video-frame"
            style={ratio === '9:16' ? { aspectRatio: '9 / 16', maxHeight: 260, margin: '0 auto', width: 146 } : ratio === '1:1' ? { aspectRatio: '1 / 1' } : undefined}
          >
            {status === 'done' ? (
              <span className="mx-video-frame__play"><Play size={18} strokeWidth={2.4} style={{ marginLeft: 2 }} /></span>
            ) : (
              <div style={{ textAlign: 'center', color: 'rgba(250,249,245,0.7)', font: 'var(--text-caption)' }}>
                <Film size={22} strokeWidth={1.4} />
                <div style={{ marginTop: 6 }}>{status === 'running' ? '镜头正在生成…' : '还没有视频'}</div>
              </div>
            )}
            <span className="mx-video-frame__cap">
              {status === 'done' ? `00:${String(duration).padStart(2, '0')} · 1080P · ${ratio}` : `${ratio} · ${duration}s`}
            </span>
          </div>

          <div className="mx-filmstrip" aria-hidden>
            {Array.from({ length: frames }).map((_, i) => (
              <i key={i} className={status === 'done' || progress > (i + 1) * (100 / frames) ? 'is-on' : ''} />
            ))}
          </div>
          <div className="mx-progress-note">
            <span>{status === 'running' ? '任务运行中 · 可切到别的会话继续干活' : status === 'done' ? '已完成 · 结果已入素材' : '待提交'}</span>
            <span style={{ fontFamily: 'var(--font-code)' }}>{status === 'running' ? `${progress}%` : status === 'done' ? '100%' : '—'}</span>
          </div>

          <div className="mx-win mx-win--plain" style={{ boxShadow: 'none' }}>
            <div style={{ padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>
              {[
                ['排队', '已受理，等待算力', status !== 'idle'],
                ['生成', '模型正在出片', status === 'running' || status === 'done'],
                ['完成', '写入产出素材', status === 'done'],
              ].map(([title, desc, on], i) => (
                <div key={String(title)} style={{ display: 'flex', alignItems: 'center', gap: 8, opacity: on ? 1 : 0.45 }}>
                  <span
                    style={{
                      width: 6, height: 6, borderRadius: '50%',
                      background: on ? (status === 'done' ? 'var(--color-success)' : 'var(--color-primary)') : 'var(--color-hairline)',
                    }}
                  />
                  <span style={{ font: 'var(--text-caption)', color: 'var(--color-ink)', minWidth: 28 }}>{title}</span>
                  <span className="mx-note" style={{ fontSize: 10 }}>{desc}</span>
                  <span className="mx-mono" style={{ marginLeft: 'auto', color: 'var(--color-muted-soft)', fontSize: 9 }}>
                    {i === 0 ? (status === 'idle' ? '待提交' : '已受理') : i === 1 ? (status === 'running' ? `${progress}%` : status === 'done' ? '100%' : '—') : (status === 'done' ? 'OK' : '—')}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 15 秒上限的可视化：单段直出 vs 分镜拼接。 */
export function DurationScale() {
  return (
    <div className="mx-stack-8">
      <div>
        <div className="mx-field__label" style={{ marginBottom: 6 }}>
          <b>单段直出</b>
          <span>4–15 秒</span>
        </div>
        <div style={{ display: 'flex', gap: 3 }}>
          <i style={{ flex: 1, height: 8, borderRadius: 3, background: 'var(--color-primary)' }} />
          <i style={{ flex: 3, height: 8, borderRadius: 3, background: 'var(--color-surface-cream-strong)' }} />
        </div>
      </div>
      <div>
        <div className="mx-field__label" style={{ marginBottom: 6 }}>
          <b>长视频</b>
          <span>分镜拼接，时长按镜累加</span>
        </div>
        <div style={{ display: 'flex', gap: 3 }}>
          {Array.from({ length: 8 }).map((_, i) => (
            <i
              key={i}
              style={{
                flex: 1, height: 8, borderRadius: 3,
                background: i < 7 ? 'var(--color-primary)' : 'var(--color-surface-cream-strong)',
                opacity: i < 7 ? 1 - i * 0.08 : 1,
              }}
            />
          ))}
        </div>
      </div>
      <div className="mx-note">
        15 秒不是产品限制，是单次生成模型的物理上限。超过就拆镜：一条 2 分钟的片子 ≈ 8–12 个镜头（每镜 10–15 秒）。
      </div>
    </div>
  );
}
