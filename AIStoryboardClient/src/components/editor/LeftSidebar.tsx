import { useState, useRef } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { AspectRatioSelector } from '../common/AspectRatioSelector';
import { ProjectHistoryPanel } from './ProjectHistoryPanel';
import { Button } from '@/components/ui/button';

const creationTypes = [
  { value: 'movie', label: '电影片段' },
  { value: 'commercial', label: '广告视频' },
  { value: 'music_video', label: '音乐视频' },
  { value: 'animation', label: '动画短片' },
  { value: 'trailer', label: '预告片' },
  { value: 'custom', label: '自定义' },
];

const expandedWidth = 280;
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

export function LeftSidebar() {
  const {
    currentProject,
    isLoading,
    generateScript,
    createProject,
    imageModel,
    videoModel,
    setImageModel,
    setVideoModel,
  } = useProjectStore();

  const [collapsed, setCollapsed] = useState(false);
  const [creationType, setCreationType] = useState('movie');
  const [customTypeDesc, setCustomTypeDesc] = useState('');
  const [aspectRatio, setAspectRatio] = useState('16:9');
  const [scriptText, setScriptText] = useState('');
  const [_refImageFile, setRefImageFile] = useState<File | null>(null);
  const [refImagePreview, setRefImagePreview] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

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
        const p = await createProject('未命名项目', creationType, aspectRatio);
        projectId = p.id;
      } catch {
        return;
      }
    }

    await generateScript(projectId, scriptText, creationType, aspectRatio, undefined);
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
        title="展开菜单"
      >
        <span
          style={{
            writingMode: 'vertical-rl',
            fontSize: 13,
            color: 'var(--color-muted)',
            letterSpacing: 2,
          }}
        >
          菜单 ▶
        </span>
      </div>
    );
  }

  return (
    <div
      style={{
        width: expandedWidth,
        minWidth: expandedWidth,
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

      {/* Model selectors */}
      <div>
        <label style={labelStyle}>生图模型</label>
        <select
          value={imageModel}
          onChange={(e) => setImageModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          <option value="gpt-image-2">GPT Image 2</option>
          <option value="gemini-3-pro-image-preview">Gemini 3 Pro Image</option>
        </select>
      </div>
      <div>
        <label style={labelStyle}>生视频模型</label>
        <select
          value={videoModel}
          onChange={(e) => setVideoModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}
        >
          <option value="veo-3.1-fast">Veo 3.1 Fast</option>
          <option value="veo-3.1">Veo 3.1</option>
        </select>
      </div>

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
            minHeight: 120,
            resize: 'none',
            lineHeight: 1.55,
          }}
        />
      </div>

      {/* Reference image upload — shadcn Button + hidden file input */}
      <div>
        <label style={labelStyle}>风格参考图（可选）</label>
        <Button variant="outline" size="sm" onClick={() => fileInputRef.current?.click()}>
          选择参考图
        </Button>
        <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={handleRefImage} />
        {refImagePreview && (
          <img
            src={refImagePreview}
            style={{
              width: '100%',
              maxHeight: 100,
              objectFit: 'cover',
              borderRadius: 8,
              marginTop: 8,
            }}
          />
        )}
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

      {/* Divider */}
      <div style={{ borderTop: '1px solid var(--color-hairline)' }} />

      {/* Project history */}
      <ProjectHistoryPanel />
    </div>
  );
}
