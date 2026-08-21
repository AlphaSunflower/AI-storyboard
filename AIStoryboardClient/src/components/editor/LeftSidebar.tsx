import { useState, useRef } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { ProjectHistoryPanel } from './ProjectHistoryPanel';
import { REFERENCE_LIMITS, type UnderstandingModelParams } from '../../config';
import { useAgentStore } from '../../stores/agentStore';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';

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

const sectionHeaderStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: 'var(--color-muted)',
  textTransform: 'uppercase' as React.CSSProperties['textTransform'],
  letterSpacing: '.5px',
  paddingTop: 4,
  borderTop: '1px solid var(--color-hairline)',
  display: 'flex',
  alignItems: 'center',
  gap: 6,
};

export function LeftSidebar() {
  const {
    currentProject,
    isLoading,
    generateScript,
    createProject,
    imageModel,
    videoModel,
    imageModelOptions,
    videoModelOptions,
    understandingModel,
    understandingModelOptions,
    videoPreset,
    setImageModel,
    setVideoModel,
    setUnderstandingModel,
  } = useProjectStore();

  // 智能体已生成分镜时，手动剧本输入与生成按钮互斥禁用
  const agentGeneratedScenes = useAgentStore((s) => s.agentGeneratedScenes);

  const [collapsed, setCollapsed] = useState(false);
  const [creationType, setCreationType] = useState('movie');
  const [customTypeDesc, setCustomTypeDesc] = useState('');
  const [scriptText, setScriptText] = useState('');
  // 参考图（base64 data URI 列表；作为理解模型的看图输入）
  const [refImages, setRefImages] = useState<string[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 上传约束：优先当前理解模型 params（网关 model_params refImages/maxImageSizeMB），未配置回退静态兜底
  const uParams = understandingModelOptions.find((m) => m.value === understandingModel)?.params as UnderstandingModelParams | undefined;
  const maxCount = uParams?.refImages?.max ?? REFERENCE_LIMITS.image.maxCount;
  const maxSizeMB = uParams?.maxImageSizeMB ?? REFERENCE_LIMITS.image.maxSizeMB;

  const handleRefImages = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    const available = maxCount - refImages.length;
    if (files.length > available) {
      alert(`最多上传 ${maxCount} 张参考图（当前已 ${refImages.length} 张）`);
    }
    const accepted = files.slice(0, Math.max(0, available));
    if (accepted.some((f) => f.size > maxSizeMB * 1024 * 1024)) {
      alert(`单张图片不能超过 ${maxSizeMB}MB`);
    }
    const valid = accepted.filter((f) => f.size <= maxSizeMB * 1024 * 1024);
    Promise.all(
      valid.map((f) => new Promise<string>((resolve) => {
        const r = new FileReader();
        r.onloadend = () => resolve(r.result as string);
        r.readAsDataURL(f);
      }))
    ).then((uris) => setRefImages((prev) => [...prev, ...uris]));
    // 允许重复选择同一文件：重置 input value
    e.target.value = '';
  };

  const removeRefImage = (idx: number) => {
    setRefImages((prev) => prev.filter((_, i) => i !== idx));
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
        alert('创建项目失败，请重试');
        return;
      }
    }

    const preset = resolveVideoPreset(videoPreset);
    await generateScript(
      projectId, scriptText, creationType, preset.aspectRatio, undefined,
      refImages.length ? understandingModel : undefined,
      refImages.length ? refImages : undefined
    );
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
          菜单 <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
      </div>
    );
  }

  return (
    <div
      style={{
        width: expandedWidth,
        minWidth: expandedWidth,
        height: '100%',
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

      {/* Creation type */}
      <div style={{ flexShrink: 0 }}>
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
          disabled={agentGeneratedScenes}
          placeholder={agentGeneratedScenes ? '分镜已由智能体生成，如需手动生成请刷新页面' : '输入剧本或创作描述，AI 将自动拆解为分镜...'}
          style={{
            ...sharedInputStyle,
            minHeight: 100,
            resize: 'vertical',
            lineHeight: 1.55,
            background: agentGeneratedScenes ? 'var(--color-primary-disabled)' : 'white',
            cursor: agentGeneratedScenes ? 'not-allowed' : 'text',
          }}
        />
      </div>

      {/* Reference image multi-upload（理解模型看图输入） */}
      <div style={{ flexShrink: 0 }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '8px 10px', borderRadius: 'var(--rounded-md)',
          border: '1px dashed var(--color-hairline)', cursor: 'pointer',
          background: 'var(--color-canvas)',
        }} onClick={() => fileInputRef.current?.click()}>
          <span style={{ fontSize: 11, color: 'var(--color-muted)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="10" cy="10" r="8.5"/><line x1="10" y1="6" x2="10" y2="14"/><line x1="6" y1="10" x2="14" y2="10"/></svg> 上传参考图（可选，最多 {maxCount} 张 / 单张 ≤{maxSizeMB}MB）
          </span>
        </div>
        <input ref={fileInputRef} type="file" accept="image/*" multiple hidden onChange={handleRefImages} />
        {refImages.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
            {refImages.map((uri, i) => (
              <div key={i} style={{ position: 'relative' }}>
                <img
                  src={uri}
                  style={{
                    width: 56,
                    height: 56,
                    objectFit: 'contain',
                    borderRadius: 'var(--rounded-sm)',
                    background: 'var(--color-surface-soft)',
                    border: '1px solid var(--color-hairline)',
                  }}
                />
                <span
                  onClick={() => removeRefImage(i)}
                  style={{
                    position: 'absolute', top: -5, right: -5,
                    background: 'var(--color-error)', color: 'white',
                    borderRadius: '50%', width: 16, height: 16, fontSize: 11,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    cursor: 'pointer',
                  }}
                >
                  ×
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ═══════════ 生图区域（只保留模型）═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="13.5" cy="6.5" r=".5"/><circle cx="17.5" cy="10.5" r=".5"/><circle cx="8.5" cy="7.5" r=".5"/><circle cx="6.5" cy="12.5" r=".5"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 011.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/></svg> 生图设置</div>

      <div style={{ flexShrink: 0 }}>
        <label style={labelStyle}>生图模型</label>
        <Select value={imageModel} onValueChange={(v) => v && setImageModel(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {imageModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* ═══════════ 生视频区域（只保留模型）═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> 生视频设置</div>

      <div style={{ flexShrink: 0 }}>
        <label style={labelStyle}>生视频模型</label>
        <Select value={videoModel} onValueChange={(v) => v && setVideoModel(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {videoModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* ═══════════ 理解设置 ═══════════ */}
      <div style={sectionHeaderStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9.5 2A5.5 5.5 0 004 7.5c0 1.1.4 2.2 1 3.1A5.48 5.48 0 004 14a5.5 5.5 0 004.5 5.4V22h3v-2.6A5.5 5.5 0 0020 14c0-1.3-.5-2.6-1.3-3.5.7-.9 1.1-2 1.1-3.2A5.5 5.5 0 0014.5 2h-5z"/><path d="M12 2v20"/></svg> 理解设置</div>

      <div style={{ flexShrink: 0 }}>
        <label style={labelStyle}>理解模型</label>
        <Select value={understandingModel} onValueChange={(v) => v && setUnderstandingModel(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {understandingModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Generate button */}
      <button
        onClick={handleGenerate}
        disabled={isLoading || !scriptText.trim() || agentGeneratedScenes}
        style={{
          flexShrink: 0,
          width: '100%',
          padding: '10px',
          height: 40,
          borderRadius: 'var(--rounded-md)',
          border: 'none',
          background:
            isLoading || !scriptText.trim() || agentGeneratedScenes
              ? 'var(--color-primary-disabled)'
              : 'var(--color-primary)',
          color: 'var(--color-on-primary)',
          fontSize: 14,
          fontWeight: 500,
          cursor: isLoading || !scriptText.trim() || agentGeneratedScenes ? 'not-allowed' : 'pointer',
        }}
      >
        {agentGeneratedScenes ? '已由智能体生成' : isLoading ? '生成中...' : '生成分镜脚本'}
      </button>

      {/* Divider + project history — independently scrollable */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflowY: 'scroll', minHeight: 120 }}>
        <div style={{ borderTop: '1px solid var(--color-hairline)', marginBottom: 8 }} />
        <div style={{
          font: 'var(--text-caption-upper)',
          fontSize: 11,
          color: 'var(--color-muted)',
          marginBottom: 6,
          flexShrink: 0,
        }}>
          历史项目
        </div>
        <ProjectHistoryPanel />
      </div>
    </div>
  );
}
