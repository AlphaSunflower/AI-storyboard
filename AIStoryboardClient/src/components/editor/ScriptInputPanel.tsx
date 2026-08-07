import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { VideoPresetSelector } from '../common/VideoPresetSelector';
import { ProjectHistoryPanel } from './ProjectHistoryPanel';
import { IMAGE_MODELS, VIDEO_MODELS, VIDEO_PRESETS, DEFAULT_VIDEO_PRESET, IMAGE_SIZES, IMAGE_QUALITIES } from '../../config';

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
    videoPreset,
    imageSize,
    imageQuality,
    setImageModel,
    setVideoModel,
    setVideoPreset,
    setImageSize,
    setImageQuality,
  } = useProjectStore();

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
        const preset = VIDEO_PRESETS.find(p => p.value === videoPreset) || VIDEO_PRESETS.find(p => p.value === DEFAULT_VIDEO_PRESET)!;
        const p = await createProject('未命名项目', creationType, preset.aspectRatio);
        projectId = p.id;
      } catch {
        return;
      }
    }

    const preset = VIDEO_PRESETS.find(p => p.value === videoPreset) || VIDEO_PRESETS.find(p => p.value === DEFAULT_VIDEO_PRESET)!;
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
          onChange={(e) => setImageModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {IMAGE_MODELS.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
        </select>
      </div>
      <div>
        <label style={labelStyle}>生图尺寸</label>
        <select
          value={imageSize}
          onChange={(e) => setImageSize(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {IMAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>
      <div>
        <label style={labelStyle}>生图质量</label>
        <select
          value={imageQuality}
          onChange={(e) => setImageQuality(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {IMAGE_QUALITIES.map(q => <option key={q} value={q}>{q}</option>)}
        </select>
      </div>

      {/* ═══════════ 生视频区域 ═══════════ */}
      <div style={sectionHeaderStyle}>🎬 生视频设置</div>

      <div>
        <label style={labelStyle}>生视频模型</label>
        <select
          value={videoModel}
          onChange={(e) => setVideoModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          {VIDEO_MODELS.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
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
