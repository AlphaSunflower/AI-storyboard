import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { AspectRatioSelector } from '../common/AspectRatioSelector';

const creationTypes = [
  { value: 'movie', label: '电影片段' },
  { value: 'commercial', label: '广告视频' },
  { value: 'music_video', label: '音乐视频' },
  { value: 'animation', label: '动画短片' },
  { value: 'trailer', label: '预告片' },
  { value: 'custom', label: '自定义' },
];

const leftPanelWidth = 320;

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

export function ScriptInputPanel() {
  const {
    currentProject,
    isLoading,
    generateScript,
    createProject,
  } = useProjectStore();

  const [creationType, setCreationType] = useState('movie');
  const [customTypeDesc, setCustomTypeDesc] = useState('');
  const [aspectRatio, setAspectRatio] = useState('16:9');
  const [scriptText, setScriptText] = useState('');

  const handleGenerate = async () => {
    if (!scriptText.trim()) return;

    let projectId = currentProject?.id;

    // If no current project, create one first
    if (!projectId) {
      try {
        const p = await createProject('未命名项目', creationType, aspectRatio);
        projectId = p.id;
      } catch {
        return;
      }
    }

    await generateScript(projectId, scriptText, creationType, aspectRatio, undefined);
  };

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
      {/* Section title */}
      <h2
        style={{
          font: 'var(--text-title-sm)',
          color: 'var(--color-ink)',
          margin: 0,
        }}
      >
        剧本输入
      </h2>

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

      {/* Aspect ratio */}
      <div>
        <label style={labelStyle}>画幅比例</label>
        <AspectRatioSelector value={aspectRatio} onChange={setAspectRatio} />
      </div>

      {/* Script textarea */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <label style={labelStyle}>剧本 / 描述</label>
        <textarea
          value={scriptText}
          onChange={(e) => setScriptText(e.target.value)}
          placeholder="输入剧本或创作描述，AI 将自动拆解为分镜..."
          style={{
            ...sharedInputStyle,
            flex: 1,
            minHeight: 200,
            resize: 'none',
            lineHeight: 1.55,
          }}
        />
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
    </div>
  );
}
