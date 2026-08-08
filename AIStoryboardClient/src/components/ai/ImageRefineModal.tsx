import { useState } from 'react';
import type { SceneResponse } from '../../api/projects';
import { assetUrl, DEFAULT_IMAGE_MODEL } from '../../config';
import { useProjectStore } from '../../stores/projectStore';

interface ImageRefineModalProps {
  scene: SceneResponse;
  onClose: () => void;
  onGenerate: (params: { prompt: string; model: string }) => void;
}

/* ── overlay / backdrop ── */
const backdrop: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,.45)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000,
};

/* ── modal shell ── */
const shell: React.CSSProperties = {
  background: 'var(--color-canvas)',
  borderRadius: 'var(--rounded-md)',
  boxShadow: '0 16px 48px rgba(0,0,0,.15), 0 0 0 1px var(--color-hairline-soft)',
  width: 520,
  maxWidth: 'calc(100vw - 32px)',
  maxHeight: 'calc(100vh - 64px)',
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
};

/* ── shared field label ── */
const labelStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--color-muted)',
  marginBottom: 4,
  display: 'block',
};

/* ── input / textarea ── */
const inputBase: React.CSSProperties = {
  width: '100%',
  fontSize: 12,
  padding: '8px 10px',
  borderRadius: 'var(--rounded-sm)',
  border: '1px solid var(--color-hairline)',
  background: 'white',
  color: 'var(--color-ink)',
  boxSizing: 'border-box',
  fontFamily: 'inherit',
  outline: 'none',
};

const selectStyle: React.CSSProperties = {
  ...inputBase,
  cursor: 'pointer',
  appearance: 'auto' as React.CSSProperties['appearance'],
  paddingRight: 28,
};

export function ImageRefineModal({ scene, onClose, onGenerate }: ImageRefineModalProps) {
  const [prompt, setPrompt] = useState(scene.imagePrompt || '');
  const [model, setModel] = useState(DEFAULT_IMAGE_MODEL);
  // 模型下拉选项：网关下发优先，静态默认兜底（见 projectStore.fetchAiModels）
  const imageModelOptions = useProjectStore((s) => s.imageModelOptions);

  const hasCurrentImage = !!scene.imageUrl;

  const handleConfirm = async () => {
    if (!prompt.trim()) return;
    await onGenerate({ prompt: prompt.trim(), model });
    onClose();
  };

  return (
    <div style={backdrop} onClick={onClose}>
      <div style={shell} onClick={(e) => e.stopPropagation()}>
        {/* ── header ── */}
        <div
          style={{
            padding: '16px 20px 12px',
            borderBottom: '1px solid var(--color-hairline-soft)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span
            style={{
              fontSize: 15,
              fontWeight: 700,
              color: 'var(--color-ink)',
            }}
          >
            完善图片 — 分镜 {scene.sceneNumber}
          </span>
          <button
            onClick={onClose}
            style={{
              background: 'none',
              border: 'none',
              fontSize: 18,
              cursor: 'pointer',
              color: 'var(--color-muted)',
              lineHeight: 1,
              padding: '2px 4px',
            }}
          >
            ✕
          </button>
        </div>

        {/* ── body ── */}
        <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* Current image preview */}
          {hasCurrentImage && (
            <div>
              <span style={labelStyle}>当前生成图（作为改图源图）</span>
              <img
                src={assetUrl(scene.imageUrl)}
                alt={`分镜 ${scene.sceneNumber} 已生成图片`}
                style={{
                  width: '100%',
                  maxHeight: 200,
                  objectFit: 'contain',
                  borderRadius: 'var(--rounded-sm)',
                  border: '1px solid var(--color-hairline)',
                  background: '#f5f5f5',
                }}
              />
            </div>
          )}

          {/* Prompt */}
          <div>
            <span style={labelStyle}>改图提示词</span>
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={4}
              style={{
                ...inputBase,
                resize: 'vertical',
                minHeight: 72,
              }}
              placeholder="描述你希望对当前图片做出的修改…"
            />
          </div>

          {/* Model selector */}
          <div>
            <span style={labelStyle}>生成模型</span>
            <select
              value={model}
              onChange={(e) => setModel(e.target.value)}
              style={selectStyle}
            >
              {imageModelOptions.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* ── footer ── */}
        <div
          style={{
            padding: '12px 20px 16px',
            borderTop: '1px solid var(--color-hairline-soft)',
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 8,
          }}
        >
          <button
            onClick={onClose}
            style={{
              padding: '7px 18px',
              fontSize: 12,
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              background: 'white',
              color: 'var(--color-muted)',
              cursor: 'pointer',
              fontWeight: 500,
            }}
          >
            取消
          </button>
          <button
            onClick={handleConfirm}
            disabled={!prompt.trim()}
            style={{
              padding: '7px 18px',
              fontSize: 12,
              borderRadius: 'var(--rounded-sm)',
              border: 'none',
              background: !prompt.trim() ? 'var(--color-hairline)' : 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              cursor: !prompt.trim() ? 'not-allowed' : 'pointer',
              fontWeight: 600,
              opacity: !prompt.trim() ? 0.6 : 1,
            }}
          >
            确认生成
          </button>
        </div>
      </div>
    </div>
  );
}
