import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { VideoPresetSelector, resolveVideoPreset } from '../common/VideoPresetSelector';
import { ProjectHistoryPanel } from './ProjectHistoryPanel';
import { IMAGE_SIZES, IMAGE_QUALITIES, type ImageModelParams } from '../../config';
import SpecularButton from '../SpecularButton';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';

const creationTypes = [
  { value: 'movie', label: '电影片段' },
  { value: 'commercial', label: '广告视频' },
  { value: 'music_video', label: '音乐视频' },
  { value: 'animation', label: '动画短片' },
  { value: 'trailer', label: '预告片' },
  { value: 'custom', label: '自定义' },
];

const leftPanelWidth = 320;
const collapsedWidth = 36;

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 12,
  color: 'var(--color-muted)',
  marginBottom: 4,
  font: 'var(--text-caption-upper)',
};

const sharedInputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  borderRadius: 'var(--rounded-md)',
  border: '1px solid var(--color-hairline)',
  fontSize: 13,
  background: 'white',
  color: 'var(--color-body)',
  outline: 'none',
};

const sectionHeaderStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: 'var(--color-muted)',
  textTransform: 'uppercase' as React.CSSProperties['textTransform'],
  letterSpacing: '.5px',
  paddingTop: 4,
  borderTop: '1px solid var(--color-hairline)',
};

export function ScriptInputPanel() {
  const {
    currentProject,
    isLoading,
    generateScript,
    createProject,
    imageModel,
    videoModel,
    imageModelOptions,
    videoModelOptions,
    videoPreset,
    imageSize,
    imageQuality,
    imageN,
    setImageModel,
    setVideoModel,
    setVideoPreset,
    setImageSize,
    setImageQuality,
    setImageN,
  } = useProjectStore();

  // 当前生图模型的参数能力（网关下发 params；未配置时回退静态 IMAGE_SIZES/IMAGE_QUALITIES）
  const imageParams = imageModelOptions.find((m) => m.value === imageModel)?.params as ImageModelParams | undefined;
  const sizeOptions = imageParams?.sizes?.length ? imageParams.sizes : [...IMAGE_SIZES];
  const qualityOptions = imageParams?.qualities?.length ? imageParams.qualities : [...IMAGE_QUALITIES];
  // 数量可选值（仅当前模型 params 配置了 n 能力时非空）：min..max 连续整数
  const nRange = imageParams?.n;
  const nOptions = nRange
    ? Array.from(
        { length: Math.max(0, (nRange.max ?? nRange.min ?? 1) - (nRange.min ?? 1) + 1) },
        (_, i) => (nRange.min ?? 1) + i
      )
    : [];

  const [collapsed, setCollapsed] = useState(false);
  const [creationType, setCreationType] = useState('movie');
  const [customTypeDesc, setCustomTypeDesc] = useState('');
  const [scriptText, setScriptText] = useState('');
  const [_refImageFile, setRefImageFile] = useState<File | null>(null);
  const [refImagePreview, setRefImagePreview] = useState('');

  const handleRefImage = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setRefImageFile(file);
      const reader = new FileReader();
      reader.onloadend = () => {
        setRefImagePreview(reader.result as string);
      };
      reader.readAsDataURL(file);
    }
  };

  const handleGenerate = async () => {
    if (!scriptText.trim()) return;

    let projectId = currentProject?.id;

    // If no current project, create one first
    if (!projectId) {
      try {
        const preset = resolveVideoPreset(videoPreset);
        const p = await createProject('未命名项目', creationType, preset.aspectRatio);
        projectId = p.id;
      } catch {
        return;
      }
    }

    const preset = resolveVideoPreset(videoPreset);
    await generateScript(projectId, scriptText, creationType, preset.aspectRatio, undefined);
  };

  // ── Collapsed state: vertical tab ──
  if (collapsed) {
    return (
      <div
        onClick={() => setCollapsed(false)}
        style={{
          width: collapsedWidth,
          minWidth: collapsedWidth,
          borderRight: '1px solid var(--color-hairline)',
          background: 'var(--color-canvas)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
          userSelect: 'none',
        }}
        title="展开剧本输入面板"
      >
        <span
          style={{
            writingMode: 'vertical-rl',
            fontSize: 13,
            color: 'var(--color-muted)',
            letterSpacing: 2,
          }}
        >
          剧本输入 <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
      </div>
    );
  }

  return (
    <div
      style={{
        width: leftPanelWidth,
        minWidth: leftPanelWidth,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-canvas)',
        padding: 'var(--space-md)',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        overflowY: 'auto',
      }}
    >
      {/* Section title with collapse button */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h2
          style={{
            font: 'var(--text-title-sm)',
            color: 'var(--color-ink)',
            margin: 0,
          }}
        >
          剧本输入
        </h2>
        <button
          onClick={() => setCollapsed(true)}
          title="折叠面板"
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            fontSize: 14,
            color: 'var(--color-muted)',
            padding: '2px 6px',
            borderRadius: 'var(--rounded-sm)',
            lineHeight: 1,
          }}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
      </div>

      {/* ═══════════ 剧本输入区域 ═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 013 3L7 19l-4 1 1-4z"/></svg> 剧本输入</div>

      {/* Creation type */}
      <div>
        <label style={labelStyle}>创作类型</label>
        <Select value={creationType} onValueChange={(v) => v && setCreationType(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {creationTypes.map((t) => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Custom type description (only when custom selected) */}
      {creationType === 'custom' && (
        <div>
          <label style={labelStyle}>自定义描述</label>
          <input
            type="text"
            value={customTypeDesc}
            onChange={(e) => setCustomTypeDesc(e.target.value)}
            placeholder="描述你的创作类型..."
            style={sharedInputStyle}
          />
        </div>
      )}

      {/* Script textarea */}
      <div style={{ flexShrink: 0, display: 'flex', flexDirection: 'column' }}>
        <label style={labelStyle}>剧本 / 描述</label>
        <textarea
          value={scriptText}
          onChange={(e) => setScriptText(e.target.value)}
          placeholder="输入剧本或创作描述，AI 将自动拆解为分镜..."
          style={{
            ...sharedInputStyle,
            minHeight: 120,
            resize: 'vertical',
            lineHeight: 1.55,
          }}
        />
      </div>

      {/* Reference image upload */}
      <div>
        <label style={labelStyle}>风格参考图（可选）</label>
        <input type="file" accept="image/*" onChange={handleRefImage} />
        {refImagePreview && (
          <img
            src={refImagePreview}
            style={{
              width: '100%',
              maxHeight: 120,
              objectFit: 'cover',
              borderRadius: 'var(--rounded-md)',
              marginTop: 8,
            }}
          />
        )}
      </div>

      {/* ═══════════ 生图区域 ═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="13.5" cy="6.5" r=".5"/><circle cx="17.5" cy="10.5" r=".5"/><circle cx="8.5" cy="7.5" r=".5"/><circle cx="6.5" cy="12.5" r=".5"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 011.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/></svg> 生图设置</div>

      <div>
        <label style={labelStyle}>生图模型</label>
        <Select value={imageModel} onValueChange={(m) => {
            if (!m) return;
            setImageModel(m);
            const p = imageModelOptions.find((o) => o.value === m)?.params as ImageModelParams | undefined;
            if (p?.sizes?.length) setImageSize(p.sizeDefault && p.sizes.includes(p.sizeDefault) ? p.sizeDefault : p.sizes[0]);
            if (p?.qualities?.length) setImageQuality(p.qualityDefault && p.qualities.includes(p.qualityDefault) ? p.qualityDefault : p.qualities[0]);
            if (p?.n) setImageN(p.n.default ?? p.n.min ?? 1);
          }}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {imageModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <div>
        <label style={labelStyle}>生图尺寸</label>
        <Select value={imageSize} onValueChange={(v) => v && setImageSize(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {sizeOptions.map((s) => <SelectItem key={s} value={s}>{s}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <div>
        <label style={labelStyle}>生图质量</label>
        <Select value={imageQuality} onValueChange={(v) => v && setImageQuality(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {qualityOptions.map((q) => <SelectItem key={q} value={q}>{q}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {/* 数量控件：仅当前模型 params 配置了 n 能力时渲染（min..max 范围，默认 n.default） */}
      {nRange && (
        <div>
          <label style={labelStyle}>生成数量</label>
          <Select value={String(imageN)} onValueChange={(v) => { if (v) setImageN(Number(v)); }}>
            <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
            <SelectContent>
              {nOptions.map((v) => <SelectItem key={v} value={String(v)}>{v}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* ═══════════ 生视频区域 ═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> 生视频设置</div>

      <div>
        <label style={labelStyle}>生视频模型</label>
        <Select value={videoModel} onValueChange={(v) => v && setVideoModel(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {videoModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Video preset — duration + aspect ratio */}
      <div>
        <label style={labelStyle}>时长和画幅</label>
        <VideoPresetSelector value={videoPreset} onChange={setVideoPreset} />
      </div>

      {/* Generate button */}
      <SpecularButton
        size="md"
        radius={8}
        tint="#cc785c"
        tintOpacity={1}
        textColor="#ffffff"
        lineColor="#ffffff"
        baseColor="#ffffff"
        intensity={1}
        thickness={1.2}
        className="specular-button--block"
        disabled={isLoading || !scriptText.trim()}
        onClick={handleGenerate}
      >
        {isLoading ? '生成中...' : '生成分镜脚本'}
      </SpecularButton>

      {/* Project history — vertical compact list, only when expanded */}
      {!collapsed && <ProjectHistoryPanel />}
    </div>
  );
}
