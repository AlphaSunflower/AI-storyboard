import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { VideoPresetSelector, resolveVideoPreset } from '../common/VideoPresetSelector';
import { ProjectHistoryPanel } from './ProjectHistoryPanel';
import { IMAGE_SIZES, IMAGE_QUALITIES, type ImageModelParams } from '../../config';

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
          剧本输入 ▶
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
          ◀
        </button>
      </div>

      {/* ═══════════ 剧本输入区域 ═══════════ */}
      <div style={sectionHeaderStyle}>📝 剧本输入</div>

      {/* Creation type */}
      <div>
        <label style={labelStyle}>创作类型</label>
        <select
          value={creationType}
          onChange={(e) => setCreationType(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {creationTypes.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
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
      <div style={sectionHeaderStyle}>🎨 生图设置</div>

      <div>
        <label style={labelStyle}>生图模型</label>
        <select
          value={imageModel}
          onChange={(e) => {
            const m = e.target.value;
            setImageModel(m);
            // 切换模型：按新模型参数能力重置悬空值（尺寸/质量默认值，数量取 n 默认）
            const p = imageModelOptions.find((o) => o.value === m)?.params as ImageModelParams | undefined;
            if (p?.sizes?.length) setImageSize(p.sizeDefault && p.sizes.includes(p.sizeDefault) ? p.sizeDefault : p.sizes[0]);
            if (p?.qualities?.length) setImageQuality(p.qualityDefault && p.qualities.includes(p.qualityDefault) ? p.qualityDefault : p.qualities[0]);
            if (p?.n) setImageN(p.n.default ?? p.n.min ?? 1);
          }}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {imageModelOptions.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
        </select>
      </div>
      <div>
        <label style={labelStyle}>生图尺寸</label>
        <select
          value={imageSize}
          onChange={(e) => setImageSize(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {sizeOptions.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>
      <div>
        <label style={labelStyle}>生图质量</label>
        <select
          value={imageQuality}
          onChange={(e) => setImageQuality(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {qualityOptions.map(q => <option key={q} value={q}>{q}</option>)}
        </select>
      </div>
      {/* 数量控件：仅当前模型 params 配置了 n 能力时渲染（min..max 范围，默认 n.default） */}
      {nRange && (
        <div>
          <label style={labelStyle}>生成数量</label>
          <select
            value={imageN}
            onChange={(e) => setImageN(Number(e.target.value))}
            style={{ ...sharedInputStyle, cursor: 'pointer' }}
          >
            {nOptions.map((v) => (
              <option key={v} value={v}>{v}</option>
            ))}
          </select>
        </div>
      )}

      {/* ═══════════ 生视频区域 ═══════════ */}
      <div style={sectionHeaderStyle}>🎬 生视频设置</div>

      <div>
        <label style={labelStyle}>生视频模型</label>
        <select
          value={videoModel}
          onChange={(e) => setVideoModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {videoModelOptions.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
        </select>
      </div>

      {/* Video preset — duration + aspect ratio */}
      <div>
        <label style={labelStyle}>时长和画幅</label>
        <VideoPresetSelector value={videoPreset} onChange={setVideoPreset} />
      </div>

      {/* Generate button */}
      <button
        onClick={handleGenerate}
        disabled={isLoading || !scriptText.trim()}
        style={{
          width: '100%',
          padding: '10px',
          height: 40,
          borderRadius: 'var(--rounded-md)',
          border: 'none',
          background:
            isLoading || !scriptText.trim()
              ? 'var(--color-primary-disabled)'
              : 'var(--color-primary)',
          color: 'var(--color-on-primary)',
          fontSize: 14,
          fontWeight: 500,
          cursor: isLoading || !scriptText.trim() ? 'not-allowed' : 'pointer',
        }}
      >
        {isLoading ? '生成中...' : '生成分镜脚本'}
      </button>

      {/* Project history — vertical compact list, only when expanded */}
      {!collapsed && <ProjectHistoryPanel />}
    </div>
  );
}
