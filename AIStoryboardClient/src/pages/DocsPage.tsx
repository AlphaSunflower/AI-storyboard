import { useRef, useState, type ReactNode, type CSSProperties } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';
import { useNavigate } from 'react-router-dom';
import SpecularButton from '../components/SpecularButton';
import TextType from '../components/TextType';
import CardSwap, { Card } from '../components/CardSwap';
import Carousel from '../components/Carousel';
import WarpText from '../components/WarpText';
import Particles from '../components/Particles';
import ParticleText from '../components/ParticleText';
import '../styles/docs.css';

gsap.registerPlugin(ScrollTrigger, useGSAP);

/* ─────────────────────────────────────────────────────────────
 *  示意窗口（产品界面 mock）：统一视觉语言，帮助用户对照真实界面。
 * ──────────────────────────────────────────────────────────── */
function MockWindow({ title, children, light = false }: { title: string; children: ReactNode; light?: boolean }) {
  return (
    <div
      className="docs-mock"
      style={{
        background: light ? '#ffffff' : 'var(--color-surface-dark)',
        border: light ? '1px solid var(--color-hairline)' : 'none',
        borderRadius: 12,
        boxShadow: '0 16px 40px rgba(20, 20, 19, 0.16)',
        overflow: 'hidden',
        textAlign: 'left',
      }}
    >
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: 6,
          padding: '10px 14px',
          background: light ? 'var(--color-canvas)' : 'var(--color-surface-dark-elevated)',
          borderBottom: light ? '1px solid var(--color-hairline)' : '1px solid rgba(250, 249, 245, 0.06)',
        }}
      >
        <span style={{ width: 9, height: 9, borderRadius: '50%', background: 'var(--color-primary)' }} />
        <span style={{ width: 9, height: 9, borderRadius: '50%', background: '#e8a55a' }} />
        <span style={{ width: 9, height: 9, borderRadius: '50%', background: '#5db872' }} />
        <span style={{ fontSize: 12, color: light ? 'var(--color-muted)' : 'var(--color-on-dark-soft)', marginLeft: 6 }}>{title}</span>
      </div>
      <div style={{ padding: 14, fontSize: 12, color: light ? 'var(--color-body)' : 'var(--color-on-dark-soft)' }}>
        {children}
      </div>
    </div>
  );
}

/* mock 通用小件 */
const mLabel: CSSProperties = { fontSize: 10, color: 'var(--color-muted)', marginBottom: 3 };
const mInput: CSSProperties = {
  fontSize: 11, padding: '5px 8px', border: '1px solid var(--color-hairline)',
  borderRadius: 6, background: '#fff', color: 'var(--color-body)', width: '100%', boxSizing: 'border-box',
};
const mBtn: CSSProperties = {
  fontSize: 11, padding: '5px 14px', border: 'none', borderRadius: 6,
  background: 'var(--color-primary)', color: '#fff', cursor: 'default',
};
const mChip: CSSProperties = {
  fontSize: 10, padding: '2px 8px', borderRadius: 999,
  background: 'rgba(204,120,92,0.12)', color: 'var(--color-primary)',
};

/* ── 交互式示意小部件（纯本地 state，零请求、零落盘） ── */

/** 演示上传：点开文件选择器，读成 data URL 回显缩略图（不上传、不发请求、不保存本地） */
function DemoUpload({ label }: { label: string }) {
  const [thumbs, setThumbs] = useState<string[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <div>
      <div
        style={{ ...mInput, cursor: 'pointer', textAlign: 'center', color: 'var(--color-muted)' }}
        onClick={() => inputRef.current?.click()}
      >
        {label}
      </div>
      <input
        ref={inputRef} type="file" accept="image/*" multiple hidden
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          Promise.all(files.map((f) => new Promise<string>((resolve) => {
            const r = new FileReader();
            r.onloadend = () => resolve(r.result as string);
            r.readAsDataURL(f);
          }))).then((uris) => setThumbs((prev) => [...prev, ...uris]));
          e.target.value = '';
        }}
      />
      {thumbs.length > 0 && (
        <div style={{ display: 'flex', gap: 5, marginTop: 6, flexWrap: 'wrap' }}>
          {thumbs.map((uri, i) => (
            <img key={i} src={uri} alt="" style={{ width: 34, height: 34, objectFit: 'cover', borderRadius: 5, border: '1px solid var(--color-hairline)' }} />
          ))}
        </div>
      )}
    </div>
  );
}

/** 演示生成按钮：点击后短暂「⏳ 生成中…」再复位，模拟真实点击反馈 */
function DemoGenerateButton({ children }: { children: ReactNode }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      style={{ ...mBtn, cursor: 'pointer', opacity: busy ? 0.7 : 1, transition: 'opacity 0.15s ease, transform 0.12s ease' }}
      onClick={() => { if (busy) return; setBusy(true); setTimeout(() => setBusy(false), 1200); }}
    >
      {busy ? '⏳ 生成中…' : children}
    </button>
  );
}

/** 演示项目切换 + 保存：点击切换高亮、点保存变绿（草稿 → 已保存） */
function DemoProjectSwitcher() {
  const projects = ['我的短片项目', '广告提案', '动画短片'];
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [saved, setSaved] = useState(false);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <button
          onClick={() => setOpen((o) => !o)}
          style={{ ...mInput, flex: 1, textAlign: 'left', cursor: 'pointer', color: 'var(--color-ink)' }}
        >
          {projects[active]} ▾
        </button>
        <button
          onClick={() => setSaved(true)}
          style={{
            ...mChip, cursor: 'pointer', padding: '3px 8px',
            background: saved ? '#5db872' : 'transparent',
            border: saved ? '1px solid #5db872' : '1px solid var(--color-primary)',
            color: saved ? '#fff' : 'var(--color-primary)',
          }}
        >
          {saved ? '✓ 已保存' : '💾 保存'}
        </button>
      </div>
      {open && (
        <div style={{ border: '1px solid var(--color-hairline)', borderRadius: 6, overflow: 'hidden' }}>
          {projects.map((p, i) => (
            <div
              key={p}
              onClick={() => { setActive(i); setOpen(false); setSaved(false); }}
              style={{
                padding: '6px 10px', fontSize: 11, cursor: 'pointer',
                color: i === active ? 'var(--color-ink)' : 'var(--color-body)',
                background: i === active ? 'var(--color-surface-card)' : '#fff',
                borderBottom: i < projects.length - 1 ? '1px solid var(--color-hairline-soft)' : 'none',
              }}
            >
              {p}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** 演示 Moon 对话：计划模式（方案 → 人工确认 → 执行）+ 继续完善，可交互 */
function DemoMoonChat() {
  const [msgs, setMsgs] = useState<{ role: 'user' | 'ai'; text: string }[]>([
    { role: 'user', text: '把这段剧本生成 8 个分镜，黄昏色调' },
    { role: 'ai', text: '好的，我先拆分剧本… 这是分镜方案，确认后写入分镜列表。' },
  ]);
  const [stage, setStage] = useState<'plan' | 'running' | 'done'>('plan');
  const [customOpen, setCustomOpen] = useState(false);
  const [input, setInput] = useState('');

  const confirm = () => {
    if (stage !== 'plan') return;
    setStage('running');
    setTimeout(() => setStage('done'), 1400);
  };

  const send = () => {
    const t = input.trim();
    if (!t) return;
    setMsgs((prev) => [...prev, { role: 'user', text: t }, { role: 'ai', text: '收到，我来安排。（演示回复）' }]);
    setInput('');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
      {msgs.map((m, i) => (
        <div
          key={i}
          style={{
            maxWidth: '84%',
            marginLeft: m.role === 'user' ? 'auto' : 0,
            padding: '7px 10px', borderRadius: 10, fontSize: 11, lineHeight: 1.5,
            background: m.role === 'user' ? 'var(--color-primary)' : 'var(--color-surface-dark-elevated)',
            color: m.role === 'user' ? '#fff' : 'var(--color-on-dark)',
            borderBottomLeftRadius: m.role === 'ai' ? 4 : 10,
            borderBottomRightRadius: m.role === 'user' ? 4 : 10,
          }}
        >
          {m.text}
        </div>
      ))}

      {/* 人工确认卡片（HITL）：确认生成 / 自定义 / 不满意再改 */}
      <div style={{ padding: '8px 10px', borderRadius: 8, background: 'var(--color-surface-dark-soft)', fontSize: 11, color: 'var(--color-on-dark)' }}>
        🖼️ 图片生成参数（已按推荐选择，可修改）
        <div style={{ color: 'var(--color-on-dark-soft)', marginTop: 2 }}>模型 GPT Image 2 · 尺寸 1024×1024 · 个数 2 · 分辨率建议 768P</div>
        {customOpen && (
          <input
            autoFocus
            placeholder="输入你的想法…"
            style={{ width: '100%', marginTop: 6, padding: '4px 8px', fontSize: 11, borderRadius: 5, border: '1px solid rgba(204,120,92,0.5)', background: 'var(--color-surface-dark)', color: 'var(--color-on-dark)', boxSizing: 'border-box' }}
          />
        )}
        <div style={{ display: 'flex', gap: 5, marginTop: 6, flexWrap: 'wrap' }}>
          <button onClick={confirm} style={{ ...mChip, cursor: 'pointer', border: '1px solid rgba(204,120,92,0.5)' }}>
            {stage === 'plan' ? '✓ 确认生成' : stage === 'running' ? '⏳ 生成中…' : '✓ 已生成'}
          </button>
          <button onClick={() => setCustomOpen((o) => !o)} style={{ ...mChip, cursor: 'pointer', background: 'transparent', border: '1px solid rgba(204,120,92,0.5)' }}>
            ✍ 自定义
          </button>
          <button
            onClick={() => setMsgs((prev) => [...prev, { role: 'ai', text: '已按你的意见重新调整方案，请再次确认。' }])}
            style={{ ...mChip, cursor: 'pointer', background: 'transparent', border: '1px solid rgba(204,120,92,0.5)' }}
          >
            不满意，再改
          </button>
        </div>
      </div>

      {/* 产出素材 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '7px 10px', borderRadius: 8, background: 'var(--color-surface-dark-soft)', fontSize: 11, color: 'var(--color-on-dark-soft)' }}>
        📁 产出素材 · 已生成 2 张图 / 1 段视频
      </div>

      {/* 底部输入 */}
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') send(); }}
          placeholder="描述你的需求…"
          style={{ flex: 1, padding: '6px 8px', fontSize: 11, borderRadius: 6, border: '1px solid rgba(250,249,245,0.12)', background: 'var(--color-surface-dark-elevated)', color: 'var(--color-on-dark)', boxSizing: 'border-box' }}
        />
        <button onClick={send} style={{ ...mBtn, cursor: 'pointer' }}>发送</button>
      </div>
    </div>
  );
}

/* ── 复刻真实「预览」面板样式（与 PreviewPanel 一致） ── */
const pSelect: CSSProperties = { padding: '4px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)', fontSize: 12, background: 'white', color: 'var(--color-ink)', outline: 'none', cursor: 'pointer' };
const pFieldRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 };
const pFieldLabel: CSSProperties = { color: 'var(--color-muted)', width: 56, flexShrink: 0, fontSize: 11 };
const pBtnPrimary: CSSProperties = { padding: '7px 18px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none', background: 'var(--color-primary)', color: 'var(--color-on-primary)', cursor: 'pointer', fontWeight: 600 };
const pBtnGhost: CSSProperties = { padding: '7px 18px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: '1px solid var(--color-primary)', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer', fontWeight: 600 };

/** 图片生成面板（复刻 PreviewPanel 图片 tab；i2i=true 默认勾选参考图生图） */
function DemoImagePanel({ i2i = false }: { i2i?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [hasImage, setHasImage] = useState(false);
  const [saved, setSaved] = useState(false);
  const [refImage, setRefImage] = useState(i2i);
  return (
    <div style={{ fontSize: 12, color: 'var(--color-body)' }}>
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', margin: '0 0 10px' }}>预览 — 分镜 1</h2>
      <div style={{ padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', fontSize: 13, color: 'var(--color-body)', lineHeight: 1.6, marginBottom: 10 }}>
        雨夜，女主撑着伞在巷口等人，远处霓虹闪烁。
      </div>
      <div style={{ display: 'flex', gap: 4, marginBottom: 10 }}>
        <span style={{ padding: '6px 14px', fontSize: 12, fontWeight: 500, borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-card)', color: 'var(--color-ink)' }}>🖼️ 图片</span>
        <span style={{ padding: '6px 14px', fontSize: 12, fontWeight: 500, borderRadius: 'var(--rounded-sm)', color: 'var(--color-muted)' }}>🎬 视频</span>
      </div>
      {hasImage ? (
        <div style={{ height: 160, borderRadius: 'var(--rounded-md)', background: 'linear-gradient(135deg,#f0e8da,#e2d4bd)', border: '1px solid var(--color-hairline)', marginBottom: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--color-muted-soft)', fontSize: 12 }}>
          （已生成图片 · 点击放大）
        </div>
      ) : (
        <div style={{ height: 160, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-soft)', color: 'var(--color-muted-soft)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, marginBottom: 8 }}>
          {busy ? '⏳ 正在生成图片...' : '未生成图片'}
        </div>
      )}
      <div style={{ marginBottom: 10, padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', fontSize: 12, lineHeight: 1.6 }}>
        <strong style={{ color: 'var(--color-muted)' }}>图片提示词：</strong>
        <p style={{ color: 'var(--color-body)', margin: '4px 0 0' }}>黄昏的雨巷，霓虹倒影在积水里，低饱和色调，电影感构图。</p>
      </div>
      <div style={{ padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🖼️ 图片生成参数</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            {!saved && <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>}
            <button onClick={() => setSaved(true)} style={{ padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none', background: saved ? 'var(--color-primary-disabled)' : 'var(--color-primary)', color: saved ? 'var(--color-muted)' : 'white', cursor: 'pointer' }}>💾 保存参数</button>
            <button onClick={() => { setSaved(false); setRefImage(false); }} style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}>恢复全局默认</button>
          </div>
        </div>
        <div style={pFieldRow}><span style={pFieldLabel}>模型</span><select defaultValue="gpt-image-2" style={pSelect}><option>gpt-image-2</option><option>gemini-3-flash-preview</option></select></div>
        <div style={pFieldRow}><span style={pFieldLabel}>尺寸</span><select defaultValue="1024x1024" style={pSelect}><option>1024x1024</option><option>1536x1024</option><option>1024x1536</option></select></div>
        <div style={pFieldRow}><span style={pFieldLabel}>质量</span><select defaultValue="高" style={pSelect}><option>低</option><option>中</option><option>高</option></select></div>
        <div style={pFieldRow}><span style={pFieldLabel}>生成个数</span><input type="range" min={1} max={4} defaultValue={1} style={{ flex: 1, accentColor: 'var(--color-primary)' }} /></div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', marginBottom: refImage ? 8 : 0 }}>
          <input type="checkbox" checked={refImage} onChange={(e) => setRefImage(e.target.checked)} style={{ margin: 0, cursor: 'pointer', accentColor: 'var(--color-primary)' }} />
          参考图生图（以参考图为源图改图）
        </label>
        {refImage && (
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            <span style={{ width: 44, height: 44, borderRadius: 6, background: 'linear-gradient(135deg,#e8e0d2,#efe9de)', border: '1px solid var(--color-hairline)' }} />
            <span style={{ fontSize: 10, color: 'var(--color-muted)', alignSelf: 'center' }}>上传参考图（图片 / 视频 / 音频）…</span>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
          <button onClick={() => { setBusy(true); setTimeout(() => { setBusy(false); setHasImage(true); }, 1200); }} style={{ ...pBtnPrimary, opacity: busy ? 0.6 : 1, cursor: busy ? 'not-allowed' : 'pointer' }}>
            {busy ? '⏳ 生成中...' : '🖼️ 生成图片'}
          </button>
          {hasImage && <button onClick={() => setHasImage(false)} style={pBtnGhost}>✨ 完善图片</button>}
        </div>
      </div>
    </div>
  );
}

/** 视频生成面板（复刻 PreviewPanel 视频 tab；i2v=true 默认勾选「以本分镜图片为首帧」） */
function DemoVideoPanel({ i2v = false }: { i2v?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [hasVideo, setHasVideo] = useState(false);
  const [saved, setSaved] = useState(false);
  const [firstFrame, setFirstFrame] = useState(i2v);
  return (
    <div style={{ fontSize: 12, color: 'var(--color-body)' }}>
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', margin: '0 0 10px' }}>预览 — 分镜 1</h2>
      <div style={{ padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', fontSize: 13, color: 'var(--color-body)', lineHeight: 1.6, marginBottom: 10 }}>
        雨夜，女主撑着伞在巷口等人，远处霓虹闪烁。
      </div>
      <div style={{ display: 'flex', gap: 4, marginBottom: 10 }}>
        <span style={{ padding: '6px 14px', fontSize: 12, fontWeight: 500, borderRadius: 'var(--rounded-sm)', color: 'var(--color-muted)' }}>🖼️ 图片</span>
        <span style={{ padding: '6px 14px', fontSize: 12, fontWeight: 500, borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-card)', color: 'var(--color-ink)' }}>🎬 视频</span>
      </div>
      {hasVideo ? (
        <div style={{ height: 160, borderRadius: 'var(--rounded-md)', background: '#181715', marginBottom: 8, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ width: 0, height: 0, borderLeft: '16px solid var(--color-on-dark)', borderTop: '10px solid transparent', borderBottom: '10px solid transparent' }} />
        </div>
      ) : (
        <div style={{ height: 160, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-soft)', color: 'var(--color-muted-soft)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, marginBottom: 8 }}>
          {busy ? '⏳ 正在生成视频...' : '未生成视频'}
        </div>
      )}
      <div style={{ marginBottom: 10, padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', fontSize: 12, lineHeight: 1.6 }}>
        <strong style={{ color: 'var(--color-muted)' }}>视频提示词：</strong>
        <p style={{ color: 'var(--color-body)', margin: '4px 0 0' }}>镜头缓慢推进，雨滴落在伞面，霓虹在积水里晕开。</p>
      </div>
      <div style={{ padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🎬 视频生成参数</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            {!saved && <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>}
            <button onClick={() => setSaved(true)} style={{ padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none', background: saved ? 'var(--color-primary-disabled)' : 'var(--color-primary)', color: saved ? 'var(--color-muted)' : 'white', cursor: 'pointer' }}>💾 保存参数</button>
            <button onClick={() => { setSaved(false); setFirstFrame(false); }} style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}>恢复全局默认</button>
          </div>
        </div>
        <div style={pFieldRow}><span style={pFieldLabel}>模型</span><select defaultValue="MiniMax-H3" style={pSelect}><option>MiniMax-H3</option><option>veo-3</option></select></div>
        <div style={pFieldRow}><span style={pFieldLabel}>时长(秒)</span><input type="range" min={4} max={12} defaultValue={8} style={{ flex: 1, accentColor: 'var(--color-primary)' }} /></div>
        <div style={pFieldRow}><span style={pFieldLabel}>分辨率</span><select defaultValue="768P" style={pSelect}><option>768P</option><option>2K</option></select></div>
        <div style={pFieldRow}><span style={pFieldLabel}>画幅</span><select defaultValue="16:9" style={pSelect}><option>16:9</option><option>9:16</option><option>4:3</option><option>1:1</option></select></div>
        <div style={{ display: 'flex', gap: 6, marginTop: 2, marginBottom: 4 }}>
          <span style={{ flex: 1, padding: '6px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)', fontSize: 10, color: 'var(--color-muted)', background: 'white' }}>📎 参考图片</span>
          <span style={{ flex: 1, padding: '6px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)', fontSize: 10, color: 'var(--color-muted)', background: 'white' }}>🎞 参考视频</span>
          <span style={{ flex: 1, padding: '6px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)', fontSize: 10, color: 'var(--color-muted)', background: 'white' }}>🎵 参考音频</span>
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', marginTop: 4 }}>
          <input type="checkbox" checked={firstFrame} onChange={(e) => setFirstFrame(e.target.checked)} style={{ margin: 0, cursor: 'pointer', accentColor: 'var(--color-primary)' }} />
          以本分镜图片为首帧（参考素材与首帧互斥）
        </label>
        <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          <button onClick={() => { setBusy(true); setTimeout(() => { setBusy(false); setHasVideo(true); }, 1200); }} style={{ ...pBtnPrimary, opacity: busy ? 0.6 : 1, cursor: busy ? 'not-allowed' : 'pointer' }}>
            {busy ? '⏳ 生成中...' : '🎬 生成视频'}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Moon 分镜写库演示：方案确认卡片（覆盖/追加/不满意/不生成）+ 清空分镜 + 底部聊天输入 */
function DemoMoonScenes() {
  const [msgs, setMsgs] = useState<{ role: 'user' | 'ai'; text: string }[]>([
    { role: 'user', text: '把这段剧本生成 8 个分镜' },
    { role: 'ai', text: '分镜方案已生成。检测到当前项目已有 6 个分镜，请选择处理方式：' },
  ]);
  const [writeResult, setWriteResult] = useState('');
  const [cleared, setCleared] = useState(false);
  const [input, setInput] = useState('');

  const send = () => {
    const t = input.trim();
    if (!t) return;
    setInput('');
    setMsgs((prev) => [...prev, { role: 'user', text: t }]);
    if (/清空|删除/.test(t)) {
      setCleared(true);
      setMsgs((prev) => [...prev, { role: 'ai', text: '✅ 已删除全部 6 个分镜，分镜列表已清空。' }]);
    } else {
      setMsgs((prev) => [...prev, { role: 'ai', text: '收到，我来安排。（演示回复）' }]);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 7, fontSize: 12, color: 'var(--color-body)' }}>
      {/* 抽屉顶部 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingBottom: 6, borderBottom: '1px solid var(--color-hairline)' }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink)' }}>☾ Moon 智能体</span>
        <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>📁 产出素材</span>
      </div>

      {/* 消息气泡 */}
      {msgs.map((m, i) => (
        <div
          key={i}
          style={{
            maxWidth: '86%', padding: '7px 11px', borderRadius: 10, fontSize: 11.5, lineHeight: 1.55,
            ...(m.role === 'user'
              ? { marginLeft: 'auto', background: 'var(--color-primary)', color: '#fff', borderBottomRightRadius: 4 }
              : { background: 'var(--color-surface-card)', color: 'var(--color-body)', borderBottomLeftRadius: 4 }),
          }}
        >
          {m.text}
        </div>
      ))}

      {/* 方案确认卡片（HITL）：覆盖 / 追加 / 不满意 / 不生成 */}
      {!writeResult && (
        <div style={{ padding: '10px 12px', borderRadius: 10, border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)', fontSize: 11.5, color: 'var(--color-body)' }}>
          <div style={{ fontWeight: 600, color: 'var(--color-ink)', marginBottom: 4 }}>分镜方案确认</div>
          <div style={{ fontSize: 10.5, color: 'var(--color-muted)', marginBottom: 8 }}>⚠️ 覆盖导入将清空现有 6 个分镜后写入新方案</div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <button onClick={() => setWriteResult('已覆盖导入：清空旧分镜并写入新方案（8 个分镜）')} style={pBtnPrimary}>覆盖导入</button>
            <button onClick={() => setWriteResult('已追加：在现有分镜后追加 8 个新分镜')} style={pBtnGhost}>追加</button>
            <button onClick={() => setWriteResult('已按你的意见调整方案，请再次确认。')} style={{ ...pBtnGhost, border: '1px solid var(--color-hairline)', color: 'var(--color-muted)' }}>不满意</button>
            <button onClick={() => setWriteResult('已取消，未写入任何分镜。')} style={{ ...pBtnGhost, border: '1px solid var(--color-hairline)', color: 'var(--color-muted)' }}>不生成</button>
          </div>
        </div>
      )}

      {/* 写库结果 / 清空结果 */}
      {(writeResult || cleared) && (
        <div style={{ maxWidth: '86%', padding: '7px 11px', borderRadius: 10, background: 'var(--color-surface-card)', color: 'var(--color-body)', fontSize: 11.5, lineHeight: 1.55, borderBottomLeftRadius: 4 }}>
          {writeResult || '✅ 已删除全部 6 个分镜，分镜列表已清空。'}
        </div>
      )}

      {/* 清空分镜快捷按钮 */}
      {!cleared && (
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <span style={{ fontSize: 10.5, color: 'var(--color-muted)' }}>试试说「清空分镜」：</span>
          <button
            onClick={() => { setCleared(true); setMsgs((prev) => [...prev, { role: 'user', text: '清空分镜' }, { role: 'ai', text: '✅ 已删除全部 6 个分镜，分镜列表已清空。' }]); }}
            style={{ padding: '4px 12px', fontSize: 11, borderRadius: 999, border: '1px solid var(--color-primary)', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer' }}
          >
            清空分镜
          </button>
        </div>
      )}

      {/* 底部聊天输入框 */}
      <div style={{ display: 'flex', gap: 6, marginTop: 'auto', paddingTop: 6, borderTop: '1px solid var(--color-hairline)' }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') send(); }}
          placeholder="描述你的需求，或输入「清空分镜」…"
          style={{ flex: 1, padding: '7px 10px', fontSize: 11.5, borderRadius: 8, border: '1px solid var(--color-hairline)', background: 'white', color: 'var(--color-ink)', outline: 'none' }}
        />
        <button onClick={send} style={{ ...pBtnPrimary, borderRadius: 8 }}>发送</button>
      </div>
    </div>
  );
}

/** 卡片顶部迷你编辑器标题栏（三点 + 标题） */
function CardBar({ title }: { title: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '8px 12px', borderBottom: '1px solid var(--color-hairline)', background: 'var(--color-surface-card)' }}>
      <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--color-primary)' }} />
      <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#e8a55a' }} />
      <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#5db872' }} />
      <span style={{ fontSize: 11, color: 'var(--color-muted)', marginLeft: 4 }}>{title}</span>
    </div>
  );
}

/** 编辑器三栏复刻里的分镜卡片（缩略图 + 镜头号 + 状态） */
function SceneMini({ num, status, active }: { num: number; status: string; active?: boolean }) {
  return (
    <div
      style={{
        display: 'flex', gap: 4, padding: 3, borderRadius: 4,
        border: active ? '1px solid var(--color-primary)' : '1px solid var(--color-hairline)',
        background: active ? 'rgba(204,120,92,0.07)' : 'var(--color-canvas)',
      }}
    >
      <div style={{ width: 26, height: 26, borderRadius: 3, background: 'linear-gradient(135deg,#e8e0d2,#efe9de)', border: '1px solid var(--color-hairline)', flexShrink: 0 }} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 8, fontWeight: 600, color: 'var(--color-ink)' }}>镜头 {num}</div>
        <div style={{ fontSize: 7, color: 'var(--color-muted)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>黄昏雨巷，女主撑伞等待…</div>
        <div style={{ fontSize: 7, color: active ? 'var(--color-primary)' : 'var(--color-muted-soft)' }}>{status}</div>
      </div>
    </div>
  );
}

/* ── 数据 ─────────────────────────────────────────────── */
/** 核心能力速览（CardSwap 卡片，anchor 对应下方文档段落 id） */
const FEATURES = [
  { title: 'AI 分镜', desc: '剧本一键拆解成分镜镜头', anchor: 'manual-script' },
  { title: '图片生成', desc: '文生图 / 图生图 / 参考图改图', anchor: 'generate' },
  { title: '视频生成', desc: '文生视频 / 图生视频（参考图作首帧）', anchor: 'generate' },
  { title: 'Moon 智能体', desc: '对话式创作，计划模式 + 人工确认', anchor: 'moon' },
  { title: '图片理解', desc: '上传参考图，理解风格再生成', anchor: 'manual-script' },
];

const PARAMS = [
  { icon: '🧠', name: '模型', desc: '生图 / 生视频使用的 AI 模型，不同模型支持的参数可能不同。' },
  { icon: '📐', name: '尺寸 / 质量', desc: '图片的像素尺寸与画质档位（低 / 中 / 高 / 自动）。' },
  { icon: '🔢', name: '生成个数', desc: '一次生成几张图片，拖动滑杆选择。' },
  { icon: '⏱️', name: '时长 / 分辨率 / 画幅', desc: '视频的秒数、清晰度与横竖屏比例。' },
  { icon: '💾', name: '保存参数', desc: '调整完点一次，把当前参数保存到该镜头（不会每动一下就请求后端）。' },
  { icon: '↩️', name: '恢复全局默认', desc: '清除当前镜头的参数覆盖，跟随全局默认设置。' },
];

const ABILITIES = [
  { icon: '🤖', tag: '文生', name: 'AI 分镜', desc: '剧本 → 分镜方案 → 确认后写入（可覆盖 / 追加 / 不生成），不满意可持续调整。' },
  { icon: '🖼️', tag: '文生图', name: '文字生成图片', desc: '描述画面，直接生成图片（无参考图时自动完成）。' },
  { icon: '🎨', tag: '图生图', name: '参考图改图', desc: '上传参考图 + 修改诉求（如「色调调暖」），在原图基础上改出新图。' },
  { icon: '🎬', tag: '文生视频', name: '文字生成视频', desc: '描述内容 → 视频方案 → 确认后生成。' },
  { icon: '🎞️', tag: '图生视频', name: '参考图生成视频', desc: '参考图作首帧 + 视频方案 → 确认后生成。' },
  { icon: '🗑️', tag: '清空', name: '清空 / 删除分镜', desc: '说「清空分镜 / 删除分镜」→ 直接删除当前项目全部分镜。' },
  { icon: '🤝', tag: 'HITL', name: '人工介入', desc: '方案确认卡片 + 参数预选与推荐理由 + 自定义输入（确认 30 分钟过期）。' },
  { icon: '✨', tag: '完善', name: '继续完善', desc: '结果不满意 → 点「继续完善」→ 输入新诉求，Moon 重新调整再生成。' },
];

/** 计划模式（HITL）四步：理解 → 方案 → 人工确认 → 执行 */
const PLAN_STEPS = [
  { name: '理解意图', desc: '识别你要的是分镜 / 图片 / 视频 / 删除分镜，还是闲聊。' },
  { name: '出方案', desc: '给出方案并预选模型参数 + 推荐理由（如「分辨率建议 768P」）。' },
  { name: '人工确认', desc: '弹确认卡片：确认生成 / ✍ 自定义输入 / 不满意再改（30 分钟过期）。' },
  { name: '执行', desc: '确认后才写分镜（覆盖 / 追加 / 不生成）、生图、生视频；不确认不执行。' },
];

const CONV_POINTS = [
  { name: '新建对话', desc: '会话栏顶部「+ 新建对话」，一个项目可开多个独立会话。' },
  { name: '归档 / 恢复', desc: '会话项 🗂 归档，归档后可切换「🗂 已归档」查看并恢复。' },
  { name: '重命名 / 删除', desc: '✏️ 重命名，🗑️ 删除（不可撤销）。' },
  { name: '清除聊天记录', desc: '对话区右上「🧹 清除聊天记录」，重置 AI 上下文，产出素材保留。' },
];

const FAQS = [
  { q: '参数改了怎么没生效？', a: '调整后需点「💾 保存参数」；点「生成」前也会自动保存未保存的参数。' },
  { q: '上传参考图后分镜更准吗？', a: '是的。理解模型会先「看图」，把画面风格 / 内容提炼进描述，再结合你的提示词生成分镜。' },
  { q: '视频生成要多久？', a: '通常 2~5 分钟。生成期间可切换其他镜头继续操作，进度会自动恢复。' },
  { q: '生成图片想调整？', a: '点「✨ 完善图片」，输入修改诉求即可在原图基础上改。' },
  { q: 'Moon 智能体和手动生成冲突吗？', a: 'Moon 生成分镜后，手动剧本输入会暂时禁用（避免覆盖），刷新页面可恢复。' },
  { q: '如何用参考图生图 / 生视频？', a: '图片参数区勾选「参考图生图」上传参考图；或在 Moon 上传参考图后让它图生图 / 图生视频。' },
];

export function DocsPage() {
  const navigate = useNavigate();
  const rootRef = useRef<HTMLDivElement>(null);

  useGSAP(
    () => {
      if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      gsap.fromTo('.docs-hero__eyebrow', { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.5, ease: 'power2.out' });
      gsap.fromTo('.docs-hero__sub', { opacity: 0, y: 14 }, { opacity: 1, y: 0, duration: 0.5, ease: 'power2.out', delay: 0.18 });
      gsap.fromTo('.docs-hero__cta', { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.5, ease: 'power2.out', delay: 0.28 });
      gsap.utils.toArray<HTMLElement>('[data-reveal]').forEach((el) => {
        gsap.fromTo(
          el,
          { opacity: 0, y: 18 },
          {
            opacity: 1, y: 0, duration: 0.5, ease: 'power2.out',
            scrollTrigger: { trigger: el, start: 'top 88%' },
            onComplete: () => gsap.set(el, { clearProps: 'transform' }),
          },
        );
      });
    },
    { scope: rootRef },
  );

  return (
    <div ref={rootRef} className="docs-page">
      {/* 顶部导航 */}
      <nav className="docs-nav">
        <a className="docs-nav__brand" href="/editor" style={{ display: 'flex', alignItems: 'center' }}>
          <ParticleText
            text="AlphaSunflower"
            particleSize={2}
            density={2}
            color="#141413"
            highlightColor="#cc785c"
            scatter={120}
            trigger="hover"
            fontSize={18}
            fontWeight={700}
            glow={false}
            style={{ width: 168, height: 28 }}
          />
          <span>AI分镜</span>
        </a>
        <SpecularButton
          size="sm"
          radius={10}
          tintOpacity={0}
          textColor="#cc785c"
          lineColor="#cc785c"
          baseColor="#cc785c"
          intensity={0.9}
          thickness={1.2}
          onClick={() => navigate('/editor')}
        >
          进入编辑器
        </SpecularButton>
      </nav>

      {/* Hero */}
      <header className="docs-hero">
        <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
          <Particles
            particleCount={120}
            particleSpread={12}
            speed={0.15}
            particleColors={['#cc785c', '#e8a55a', '#faf9f5']}
            moveParticlesOnHover
            particleHoverFactor={0.5}
            alphaParticles
            particleBaseSize={120}
            sizeRandomness={1}
            cameraDistance={22}
          />
        </div>
        <div className="docs-hero__inner">
          <div className="docs-hero__eyebrow">AI 分镜 · 使用指南</div>
          <TextType
            as="h1"
            className="docs-hero__title"
            text="把故事，变成一帧帧画面"
            typingSpeed={60}
            initialDelay={200}
            pauseDuration={4000}
            loop={false}
            showCursor
            cursorCharacter="|"
          />
          <p className="docs-hero__sub">
            输入剧本，AI 自动拆解分镜；逐镜生成图片与视频，或召唤 Moon 智能体对话式创作。本指南覆盖全部核心功能，三分钟上手。
          </p>
          <div className="docs-hero__cta">
            <SpecularButton
              size="lg"
              radius={12}
              tint="#cc785c"
              tintOpacity={1}
              textColor="#ffffff"
              lineColor="#ffffff"
              baseColor="#ffffff"
              intensity={1}
              thickness={1.2}
              onClick={() => navigate('/editor')}
            >
              进入编辑器
            </SpecularButton>
          </div>
          <div className="docs-hero__hint">登录后即可开始 · 无需额外配置</div>
        </div>
      </header>

      {/* 核心能力速览（CardSwap 3D 卡片轮换，点击跳转对应段落） */}
      <section className="docs-section docs-section--alt">
        <div className="docs-container" data-reveal>
          <div className="docs-split docs-split--wide-left" style={{ alignItems: 'center' }}>
            <div>
              <div className="docs-section__eyebrow">核心能力</div>
              <h2 className="docs-section__title">一块画布，五种能力</h2>
              <p className="docs-section__lead">
                从剧本拆解分镜，到生成图片与视频，再到 Moon 智能体对话式创作——核心能力全在这一套工作流里。点击右侧卡片可跳到对应讲解。
              </p>
              <div className="docs-points" style={{ marginTop: 'var(--space-lg)' }}>
                {FEATURES.map((f, i) => (
                  <div className="docs-point" key={f.title}>
                    <div className="docs-point__num">{i + 1}</div>
                    <div>
                      <div className="docs-point__name">{f.title}</div>
                      <div className="docs-point__desc">{f.desc}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <div style={{ height: 480, position: 'relative' }}>
              <CardSwap
                width={320}
                height={420}
                cardDistance={40}
                verticalDistance={46}
                delay={3000}
                skewAmount={4}
                onCardClick={(idx) => {
                  const anchor = FEATURES[idx]?.anchor;
                  if (anchor) document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }}
              >
                {/* 卡 1：AI 分镜 → 剧本输入面板 */}
                <Card style={{ padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', cursor: 'pointer', background: 'var(--color-canvas)' }}>
                  <CardBar title="剧本输入" />
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
                    <div>
                      <div style={mLabel}>创作类型</div>
                      <div style={{ ...mInput }}>电影片段 ▾</div>
                    </div>
                    <div>
                      <div style={mLabel}>剧本 / 描述</div>
                      <textarea defaultValue="雨夜，女主撑着伞在巷口等人，远处霓虹闪烁…" rows={4} style={{ ...mInput, resize: 'none' }} />
                    </div>
                    <button style={mBtn}>生成分镜脚本</button>
                  </div>
                </Card>

                {/* 卡 2：图片生成 → 预览 · 图片 */}
                <Card style={{ padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', cursor: 'pointer', background: 'var(--color-canvas)' }}>
                  <CardBar title="预览 · 图片" />
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
                    <div style={{ height: 82, borderRadius: 6, background: 'linear-gradient(135deg,#f5f0e8,#e8e0d2)', border: '1px solid var(--color-hairline)' }} />
                    <div>
                      <div style={mLabel}>图片提示词</div>
                      <textarea defaultValue="黄昏的雨巷，霓虹倒影在积水里…" rows={2} style={{ ...mInput, resize: 'none' }} />
                    </div>
                    <div style={{ display: 'flex', gap: 5 }}><span style={mChip}>尺寸</span><span style={mChip}>质量</span><span style={mChip}>个数</span></div>
                    <button style={mBtn}>生成图片</button>
                  </div>
                </Card>

                {/* 卡 3：视频生成 → 预览 · 视频 */}
                <Card style={{ padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', cursor: 'pointer', background: 'var(--color-canvas)' }}>
                  <CardBar title="预览 · 视频" />
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
                    <div style={{ height: 82, borderRadius: 6, background: 'linear-gradient(135deg,#181715,#252320)', border: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      <span style={{ width: 0, height: 0, borderLeft: '12px solid var(--color-on-dark)', borderTop: '8px solid transparent', borderBottom: '8px solid transparent' }} />
                    </div>
                    <div>
                      <div style={mLabel}>视频提示词</div>
                      <textarea defaultValue="镜头缓慢推进，雨滴落在伞面…" rows={2} style={{ ...mInput, resize: 'none' }} />
                    </div>
                    <div style={{ display: 'flex', gap: 5 }}><span style={mChip}>时长</span><span style={mChip}>分辨率</span><span style={mChip}>画幅</span></div>
                    <button style={mBtn}>生成视频</button>
                  </div>
                </Card>

                {/* 卡 4：Moon 智能体 → 对话示意 */}
                <Card style={{ padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', cursor: 'pointer', background: 'var(--color-canvas)' }}>
                  <CardBar title="Moon 智能体" />
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
                    <div style={{ maxWidth: '85%', marginLeft: 'auto', padding: '7px 10px', borderRadius: 8, background: 'var(--color-primary)', color: '#fff', fontSize: 11, lineHeight: 1.5 }}>把这段剧本生成 8 个分镜</div>
                    <div style={{ maxWidth: '85%', padding: '7px 10px', borderRadius: 8, background: 'var(--color-surface-card)', color: 'var(--color-body)', fontSize: 11, lineHeight: 1.5 }}>好的，这是分镜方案，确认后写入分镜列表。</div>
                    <div style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid var(--color-hairline)', fontSize: 11, color: 'var(--color-body)' }}>
                      图片参数（已按推荐选择）
                      <div style={{ fontSize: 10, color: 'var(--color-muted)', marginTop: 2 }}>模型 GPT Image 2 · 尺寸 1024×1024 · 个数 2</div>
                    </div>
                    <div style={{ display: 'flex', gap: 5 }}><span style={mChip}>确认生成</span><span style={{ ...mChip, background: 'transparent', border: '1px solid var(--color-primary)' }}>自定义</span></div>
                  </div>
                </Card>

                {/* 卡 5：图片理解 → 理解设置 + 参考图 */}
                <Card style={{ padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', cursor: 'pointer', background: 'var(--color-canvas)' }}>
                  <CardBar title="理解设置 · 参考图" />
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
                    <div>
                      <div style={mLabel}>理解模型</div>
                      <div style={{ ...mInput }}>Gemini 3 Flash ▾</div>
                    </div>
                    <div>
                      <div style={mLabel}>上传参考图（可选）</div>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <span style={{ width: 46, height: 46, borderRadius: 6, background: 'linear-gradient(135deg,#e8e0d2,#efe9de)', border: '1px solid var(--color-hairline)' }} />
                        <span style={{ width: 46, height: 46, borderRadius: 6, background: 'linear-gradient(135deg,#efe9de,#e6dfd8)', border: '1px solid var(--color-hairline)' }} />
                        <span style={{ width: 46, height: 46, borderRadius: 6, background: 'linear-gradient(135deg,#e6dfd8,#f5f0e8)', border: '1px solid var(--color-hairline)' }} />
                      </div>
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--color-muted)', lineHeight: 1.5 }}>理解模型先「看图」，把风格与内容提炼进描述，再结合提示词生成更贴合的分镜。</div>
                  </div>
                </Card>
              </CardSwap>
            </div>
          </div>
        </div>
      </section>

      {/* 界面总览 */}
      <section className="docs-section">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">界面总览</div>
            <h2 className="docs-section__title">一个三栏工作台</h2>
            <p className="docs-section__lead">
              整个编辑器由「剧本输入 → 分镜列表 → 预览」三栏组成，右下角还有两个悬浮球：⚡ 任务中心与 ☾ Moon 智能体。
            </p>
          </div>
          <div className="docs-split">
            <div className="docs-points">
              {[
                { name: '左侧 · 剧本输入', desc: '创作类型、剧本 / 描述、参考图上传、生图 / 生视频 / 理解模型，以及历史项目。' },
                { name: '中间 · 分镜列表', desc: 'AI 拆解出的分镜镜头，点击切换，可增删镜头。' },
                { name: '右侧 · 预览', desc: '查看剧本、填写提示词、调整参数、生成图片与视频。' },
                { name: '右下角 · 两个悬浮球', desc: '⚡ 任务中心聚合进行中的生成任务；☾ 打开 Moon 智能体抽屉。' },
              ].map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
            </div>
            <MockWindow title="编辑器 · 三栏布局（真实界面复刻）" light>
              <div style={{ display: 'flex', flexDirection: 'column', position: 'relative', minHeight: 340 }}>
                {/* 顶栏 */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 10px', borderBottom: '1px solid var(--color-hairline)', background: '#fff' }}>
                  <span style={{ fontWeight: 700, fontSize: 12, color: 'var(--color-primary)', whiteSpace: 'nowrap' }}>AlphaSunflower AI分镜</span>
                  <span style={{ ...mChip, background: 'transparent', border: '1px solid var(--color-primary)', padding: '1px 8px' }}>💾 保存</span>
                  <span style={{ ...mChip, cursor: 'default' }}>我的短片项目 ▾</span>
                  <span style={{ marginLeft: 'auto', fontSize: 9, color: 'var(--color-muted)' }}>使用文档</span>
                  <span style={{ fontSize: 9, color: 'var(--color-muted)' }}>admin</span>
                  <span style={{ fontSize: 9, color: 'var(--color-muted)' }}>退出</span>
                </div>

                {/* 三栏 */}
                <div style={{ display: 'flex', flex: 1, minHeight: 250 }}>
                  {/* 左：剧本输入 */}
                  <div style={{ width: '25%', minWidth: 104, borderRight: '1px solid var(--color-hairline)', background: 'var(--color-canvas)', padding: 7, display: 'flex', flexDirection: 'column', gap: 5 }}>
                    <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--color-ink)' }}>剧本输入</div>
                    <div>
                      <div style={{ fontSize: 8, color: 'var(--color-muted)', marginBottom: 2 }}>创作类型</div>
                      <div style={{ fontSize: 9, padding: '2px 5px', border: '1px solid var(--color-hairline)', borderRadius: 4, background: '#fff', color: 'var(--color-body)' }}>电影片段 ▾</div>
                    </div>
                    <div>
                      <div style={{ fontSize: 8, color: 'var(--color-muted)', marginBottom: 2 }}>剧本 / 描述</div>
                      <div style={{ height: 44, fontSize: 8, padding: 3, border: '1px solid var(--color-hairline)', borderRadius: 4, background: '#fff', color: 'var(--color-muted-soft)', lineHeight: 1.4 }}>雨夜，女主撑着伞在巷口等人…</div>
                    </div>
                    <div style={{ fontSize: 8, color: 'var(--color-muted)' }}>📎 参考图</div>
                    <div style={{ display: 'flex', gap: 4 }}>
                      <span style={{ fontSize: 8, color: 'var(--color-body)', padding: '1px 4px', border: '1px solid var(--color-hairline)', borderRadius: 3 }}>🎨 生图模型</span>
                      <span style={{ fontSize: 8, color: 'var(--color-body)', padding: '1px 4px', border: '1px solid var(--color-hairline)', borderRadius: 3 }}>🎬 生视频模型</span>
                    </div>
                    <div style={{ fontSize: 8, color: 'var(--color-body)', padding: '1px 4px', border: '1px solid var(--color-hairline)', borderRadius: 3, width: 'fit-content' }}>🧠 理解模型</div>
                    <div style={{ fontSize: 9, textAlign: 'center', padding: '3px', borderRadius: 4, background: 'var(--color-primary)', color: '#fff' }}>生成分镜脚本</div>
                    <div style={{ fontSize: 8, color: 'var(--color-muted-soft)', marginTop: 'auto' }}>历史项目 ▾</div>
                  </div>

                  {/* 中：分镜列表 */}
                  <div style={{ width: '34%', borderRight: '1px solid var(--color-hairline)', background: '#fff', padding: 7, display: 'flex', flexDirection: 'column', gap: 5 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ fontSize: 10, fontWeight: 600, color: 'var(--color-ink)' }}>分镜列表</span>
                      <span style={{ fontSize: 8, padding: '1px 7px', border: '1px solid var(--color-hairline)', borderRadius: 4, color: 'var(--color-body)' }}>+ 添加</span>
                    </div>
                    <SceneMini num={1} status="生成中…" active />
                    <SceneMini num={2} status="已生成" />
                    <SceneMini num={3} status="已生成" />
                  </div>

                  {/* 拖拽手柄 */}
                  <div style={{ width: 3, background: 'var(--color-primary)', opacity: 0.6, flexShrink: 0 }} />

                  {/* 右：预览 */}
                  <div style={{ flex: 1, background: '#fff', padding: 7, display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0 }}>
                    <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--color-ink)' }}>预览 — 分镜 1</div>
                    <div style={{ display: 'flex', gap: 4 }}>
                      <span style={{ fontSize: 9, padding: '1px 7px', borderRadius: 4, background: 'var(--color-surface-card)', color: 'var(--color-ink)' }}>图片</span>
                      <span style={{ fontSize: 9, padding: '1px 7px', borderRadius: 4, color: 'var(--color-muted)' }}>视频</span>
                    </div>
                    <div style={{ flex: 1, borderRadius: 4, background: 'linear-gradient(135deg,#f5f0e8,#e8e0d2)', border: '1px solid var(--color-hairline)', minHeight: 70 }} />
                    <div style={{ fontSize: 8, color: 'var(--color-muted)' }}>图片提示词 · 黄昏的雨巷，霓虹倒影…</div>
                    <div style={{ fontSize: 8, color: 'var(--color-muted)' }}>🖼️ 图片生成参数 · 模型 / 尺寸 / 质量 / 个数</div>
                    <div style={{ fontSize: 9, textAlign: 'center', padding: '3px', borderRadius: 4, background: 'var(--color-primary)', color: '#fff', width: '55%' }}>生成图片</div>
                  </div>
                </div>

                {/* 右下角悬浮球 */}
                <div style={{ position: 'absolute', right: 8, bottom: 8, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 20, height: 20, borderRadius: '50%', background: 'var(--color-primary)', color: '#fff', fontSize: 10, display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: '0 2px 6px rgba(204,120,92,0.45)' }}>⚡</span>
                  <span style={{ width: 20, height: 20, borderRadius: '50%', background: 'var(--color-primary)', color: '#fff', fontSize: 11, display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: '0 2px 6px rgba(204,120,92,0.45)' }}>☾</span>
                </div>
              </div>
            </MockWindow>
          </div>
        </div>
      </section>

      {/* 手动生成分镜（含图片理解） */}
      <section className="docs-section docs-section--alt" id="manual-script">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">手动生成分镜</div>
            <h2 className="docs-section__title">先拆剧本，再逐镜成片</h2>
            <p className="docs-section__lead">
              不依赖智能体也能生成分镜：写剧本、选类型、上传参考图，AI 会「看图 + 读剧本」生成更贴合的分镜。
            </p>
          </div>
          <div className="docs-split docs-split--wide-left">
            <div className="docs-points">
              {[
                { name: '选创作类型', desc: '电影片段 / 广告视频 / 音乐视频 / 动画短片 / 预告片 / 自定义。' },
                { name: '写剧本 / 描述', desc: '把故事或画面需求写进文本框。' },
                { name: '上传参考图（可选）', desc: '可传多张。理解模型会先「看图」，把风格与内容提炼进描述，再结合你的提示词，生成更贴合的分镜。' },
                { name: '选理解模型', desc: '用于看图理解的视觉模型（默认 Gemini 3 Flash）。' },
                { name: '点「生成分镜脚本」', desc: 'AI 拆解出一个个分镜镜头，出现在中间列表。' },
              ].map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
              <div className="docs-note" style={{ marginTop: 0 }}>
                <strong>小技巧：</strong>想要某种风格（如「宫崎骏式」「低饱和胶片」），直接上传几张参考图，比用文字描述风格更准。
              </div>
            </div>
            <MockWindow title="剧本输入面板" light>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                <div>
                  <div style={mLabel}>创作类型</div>
                  <div style={{ ...mInput, color: 'var(--color-body)' }}>电影片段 ▾</div>
                </div>
                <div>
                  <div style={mLabel}>剧本 / 描述</div>
                  <textarea
                    defaultValue="雨夜，女主撑着伞在巷口等人，远处霓虹闪烁…"
                    rows={3}
                    style={{ ...mInput, minHeight: 46, lineHeight: 1.5, color: 'var(--color-body)', resize: 'vertical' }}
                  />
                </div>
                <div>
                  <div style={mLabel}>📎 上传参考图（可选，最多 10 张）</div>
                  <DemoUpload label="＋ 选择图片（演示，不真上传）" />
                </div>
                <div>
                  <div style={mLabel}>🧠 理解模型</div>
                  <div style={{ ...mInput, color: 'var(--color-body)' }}>Gemini 3 Flash ▾</div>
                </div>
                <DemoGenerateButton>生成分镜脚本</DemoGenerateButton>
              </div>
            </MockWindow>
          </div>
        </div>
      </section>

      {/* 逐镜生成图片与视频 */}
      <section className="docs-section" id="generate">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">逐镜生成</div>
            <h2 className="docs-section__title">每个镜头，单独成图成片</h2>
            <p className="docs-section__lead">
              选中一个分镜，在右侧「图片 / 视频」标签页分别生成。参数分区块设置，调完点「保存参数」一次性生效。
            </p>
          </div>
          <div className="docs-params">
            {PARAMS.map((p) => (
              <div className="docs-param" key={p.name}>
                <div className="docs-param__icon">{p.icon}</div>
                <div>
                  <div className="docs-param__name">{p.name}</div>
                  <div className="docs-param__desc">{p.desc}</div>
                </div>
              </div>
            ))}
          </div>
          <div className="docs-note">
            <strong>提示：</strong>调整参数（尤其是拖动「生成个数」「时长」滑杆）不会实时请求后端；改完点右上角「💾 保存参数」统一保存，
            直接点「生成」也会自动先保存未保存的改动再开始生成。
          </div>
          <div className="docs-split" style={{ marginTop: 'var(--space-xl)' }}>
            <MockWindow title="预览 · 图片生成" light>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                <div style={{ height: 90, borderRadius: 6, background: 'var(--color-canvas)', border: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 26 }}>🖼️</div>
                <div>
                  <div style={mLabel}>图片提示词</div>
                  <textarea defaultValue="黄昏的雨巷，霓虹倒影在积水里…" rows={2} style={{ ...mInput, color: 'var(--color-body)', resize: 'vertical' }} />
                </div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <span style={mChip}>模型</span><span style={mChip}>尺寸</span><span style={mChip}>质量</span><span style={mChip}>个数 ▸ 3</span>
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <DemoGenerateButton>🖼️ 生成图片</DemoGenerateButton>
                  <button style={{ ...mBtn, background: 'transparent', border: '1px solid var(--color-primary)', color: 'var(--color-primary)' }}>✨ 完善图片</button>
                  <span style={{ fontSize: 10, color: 'var(--color-muted)', alignSelf: 'center' }}>💾 保存参数</span>
                </div>
              </div>
            </MockWindow>
            <MockWindow title="预览 · 视频生成" light>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                <div style={{ height: 90, borderRadius: 6, background: 'var(--color-surface-dark)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 26 }}>🎬</div>
                <div>
                  <div style={mLabel}>视频提示词</div>
                  <textarea defaultValue="镜头缓慢推进，雨滴落在伞面…" rows={2} style={{ ...mInput, color: 'var(--color-body)', resize: 'vertical' }} />
                </div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <span style={mChip}>模型</span><span style={mChip}>时长 ▸ 8s</span><span style={mChip}>分辨率</span><span style={mChip}>画幅</span>
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <DemoGenerateButton>🎬 生成视频</DemoGenerateButton>
                  <span style={{ fontSize: 10, color: 'var(--color-muted)', alignSelf: 'center' }}>💾 保存参数</span>
                </div>
              </div>
            </MockWindow>
          </div>
        </div>
      </section>

      {/* 进行中的任务 */}
      <section className="docs-section docs-section--alt">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">进行中的任务</div>
            <h2 className="docs-section__title">生成进度，一球尽收</h2>
            <p className="docs-section__lead">
              右下角 ⚡ 悬浮球聚合所有进行中的任务：图片 / 视频生成、脚本生成、智能体交流与视频任务。角标显示数量，视频带进度条。
            </p>
          </div>
          <div className="docs-split">
            <div className="docs-points">
              {[
                { name: '角标数字', desc: '⚡ 球右上角的红点数字 = 当前进行中的任务数。' },
                { name: '进度条', desc: '视频任务显示实时进度百分比，生成完自动消失。' },
                { name: '点击跳转', desc: '点任务项 → 分镜任务跳转到对应镜头；智能体任务打开 Moon 抽屉。' },
              ].map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
            </div>
            <MockWindow title="⚡ 任务中心（悬浮球正上方）" light>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink)' }}>进行中的任务</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 8px', borderRadius: 6, background: 'var(--color-surface-card)' }}>
                  <span>🖼</span>
                  <div style={{ flex: 1, fontSize: 11, color: 'var(--color-ink)' }}>分镜 3 · 生成图片</div>
                  <span style={{ width: 12, height: 12, borderRadius: '50%', border: '2px solid rgba(204,120,92,0.25)', borderTopColor: 'var(--color-primary)' }} />
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 8px', borderRadius: 6, background: 'var(--color-surface-card)' }}>
                  <span>🎬</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 11, color: 'var(--color-ink)' }}>分镜 5 · 生成视频</div>
                    <div style={{ height: 4, background: 'rgba(20,20,19,0.08)', borderRadius: 2, marginTop: 4 }}>
                      <div style={{ height: '100%', width: '62%', background: 'var(--color-primary)', borderRadius: 2 }} />
                    </div>
                  </div>
                  <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>62%</span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 8px', borderRadius: 6, background: 'var(--color-surface-card)' }}>
                  <span>💬</span>
                  <div style={{ flex: 1, fontSize: 11, color: 'var(--color-ink)' }}>Moon · 正在设计视频方案</div>
                  <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>…</span>
                </div>
              </div>
            </MockWindow>
          </div>
        </div>
      </section>

      {/* 项目管理 */}
      <section className="docs-section">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">项目管理</div>
            <h2 className="docs-section__title">多项目切换与保存</h2>
            <p className="docs-section__lead">顶栏的项目下拉管理你的所有项目；「💾 保存」把草稿存为正式版，Ctrl/Cmd+S 快捷键同效。</p>
          </div>
          <div className="docs-split">
            <div className="docs-points">
              {[
                { name: '切换项目', desc: '点顶栏项目名展开列表，选择即切换；当前项目高亮。' },
                { name: '新建项目', desc: '下拉顶部「+ 新建项目」，自动创建并切换。' },
                { name: '重命名 / 删除', desc: '项目项右侧 ✏️ / 🗑️（默认项目不可删除）。' },
                { name: '保存项目', desc: '「💾 保存」把草稿（draft）存为正式版（active）。' },
              ].map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
            </div>
            <MockWindow title="顶栏 · 项目下拉（点开试试切换 / 保存）" light>
              <DemoProjectSwitcher />
            </MockWindow>
          </div>
        </div>
      </section>

      {/* Moon 智能体 */}
      <section className="docs-section docs-section--alt" id="moon">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">Moon 智能体</div>
            <h2 className="docs-section__title">对话，就能创作</h2>
            <p className="docs-section__lead">
              Moon 采用「计划模式」：先理解你的需求 → 给出方案并预选参数 → 弹确认卡片由你拍板 → 确认后才执行，不确认不执行。
              分镜、图片、视频都能生成，图生图 / 文生图 / 图生视频 / 文生视频、清空分镜、人工介入、继续完善，全在这里。
            </p>
          </div>

          <div className="docs-ability-grid" style={{ marginBottom: 'var(--space-xl)' }}>
            {ABILITIES.map((a) => (
              <div className="docs-ability" key={a.name}>
                <div className="docs-ability__top">
                  <span className="docs-ability__icon">{a.icon}</span>
                  <span className="docs-ability__tag">{a.tag}</span>
                </div>
                <div className="docs-ability__name">{a.name}</div>
                <div className="docs-ability__desc">{a.desc}</div>
              </div>
            ))}
          </div>

          <div className="docs-split docs-split--wide-left">
            <div className="docs-points">
              {PLAN_STEPS.map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
              <div className="docs-note" style={{ marginTop: 0 }}>
                <strong>参考图：</strong>输入框左侧 📎 上传图片 → 图生图 / 图生视频（以参考图为首帧）。
                生成结果在抽屉顶部「📁 产出素材」统一查看、下载、删除。
              </div>
            </div>
            <MockWindow title="☾ Moon 智能体 · 对话示意（试试确认 / 再改 / 发送）">
              <DemoMoonChat />
            </MockWindow>
          </div>

          {/* 能力示例：Carousel 左右滑动，逐个试用每种能力 */}
          <div style={{ marginTop: 'var(--space-xl)' }}>
            <div className="docs-section__eyebrow">能力示例</div>
            <h3 style={{ font: 'var(--font-display)', fontSize: 24, fontWeight: 400, color: 'var(--color-ink)', marginBottom: 'var(--space-md)' }}>左右滑动，逐个试用每种能力</h3>
            <div style={{ display: 'flex', justifyContent: 'center' }}>
              <Carousel
                baseWidth={660}
                autoplay={false}
                loop
                round={false}
                items={[
                  { id: 1, title: '文生图 · 描述即生成', content: <DemoImagePanel /> },
                  { id: 2, title: '图生图 · 参考图改图', content: <DemoImagePanel i2i /> },
                  { id: 3, title: '图生视频 · 参考图作首帧', content: <DemoVideoPanel i2v /> },
                  { id: 4, title: '文生视频 · 描述 → 方案 → 确认', content: <DemoVideoPanel /> },
                  { id: 5, title: 'AI 分镜 · 覆盖 / 追加 / 不生成 / 清空', content: <DemoMoonScenes /> },
                ]}
              />
            </div>
          </div>
        </div>
      </section>

      {/* Moon 会话管理 */}
      <section className="docs-section">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">会话管理</div>
            <h2 className="docs-section__title">一个项目，多个对话</h2>
            <p className="docs-section__lead">Moon 抽屉左侧是会话栏：新建对话、归档、重命名、删除，一个项目可并行多个独立会话。</p>
          </div>
          <div className="docs-split">
            <div className="docs-points">
              {CONV_POINTS.map((p, i) => (
                <div className="docs-point" key={p.name}>
                  <div className="docs-point__num">{i + 1}</div>
                  <div>
                    <div className="docs-point__name">{p.name}</div>
                    <div className="docs-point__desc">{p.desc}</div>
                  </div>
                </div>
              ))}
            </div>
            <MockWindow title="☾ Moon · 会话栏" light>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink)' }}>☾ Moon 智能体</div>
                <div style={{ padding: '6px', border: '1px dashed var(--color-hairline)', borderRadius: 6, fontSize: 11, color: 'var(--color-primary)', textAlign: 'center' }}>+ 新建对话</div>
                <div style={{ fontSize: 10, color: 'var(--color-muted)' }}>🗂 已归档</div>
                <div style={{ padding: '6px 8px', borderRadius: 6, background: 'var(--color-surface-card)', fontSize: 11, color: 'var(--color-ink)', display: 'flex', justifyContent: 'space-between' }}>
                  分镜方案讨论 <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>✏️ 🗂 🗑️</span>
                </div>
                <div style={{ padding: '6px 8px', borderRadius: 6, fontSize: 11, color: 'var(--color-body)', display: 'flex', justifyContent: 'space-between' }}>
                  海报图生成 <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>✏️ 🗂 🗑️</span>
                </div>
              </div>
            </MockWindow>
          </div>
        </div>
      </section>

      {/* 常见问题 */}
      <section className="docs-section docs-section--alt">
        <div className="docs-container" data-reveal>
          <div className="docs-section__head">
            <div className="docs-section__eyebrow">常见问题</div>
            <h2 className="docs-section__title">你可能想知道</h2>
          </div>
          <div className="docs-faq">
            {FAQS.map((f) => (
              <div className="docs-faq__item" key={f.q}>
                <div className="docs-faq__q">{f.q}</div>
                <div className="docs-faq__a">{f.a}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="docs-footer">
        <div className="docs-footer__inner">
          <div className="docs-footer__brand">
            <WarpText
              text="AlphaSunflower AI分镜"
              color="#cc785c"
              fontSize={22}
              fontWeight={700}
              warpStrength={0.05}
              warpScale={1.4}
              speed={0.4}
              pointerInfluence={0.3}
              pointerStrength={0.25}
              refraction={0.01}
              ripple
              style={{ width: 320, height: 48, margin: '0 auto' }}
            />
          </div>
          <div className="docs-footer__text">从剧本到分镜，再到图片与视频——让每个故事都拥有画面。</div>
        </div>
      </footer>
    </div>
  );
}
