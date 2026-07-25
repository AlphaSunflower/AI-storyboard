import { useState, useRef } from 'react';
import type { SceneResponse } from '../../api/projects';
import { assetUrl, VIDEO_MODELS, DEFAULT_VIDEO_MODEL } from '../../config';

interface VideoRefineModalProps {
  scene: SceneResponse;
  onClose: () => void;
  onGenerate: (params: { prompt: string; model: string; referenceImages: string[] }) => void;
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

export function VideoRefineModal({ scene, onClose, onGenerate }: VideoRefineModalProps) {
  const [prompt, setPrompt] = useState(scene.videoPrompt || '');
  const [model, setModel] = useState(DEFAULT_VIDEO_MODEL);
  const [extraRefs, setExtraRefs] = useState<string[]>([]);
  const fileRef = useRef<HTMLInputElement>(null);

  // Use scene.imageUrl as the base reference (video generation often uses
  // the still image as a starting frame).
  const hasCurrentImage = !!scene.imageUrl;
  const hasCurrentVideo = !!scene.videoUrl;
  const maxExtra = hasCurrentImage ? 3 : 4;

  const allRefImages: string[] = [
    ...(hasCurrentImage ? [scene.imageUrl] : []),
    ...extraRefs,
  ];
  const totalRefs = allRefImages.length;

  const handleUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (extraRefs.length + files.length > maxExtra) {
      alert(`最多再添加 ${maxExtra} 张参考图`);
      return;
    }
    files.forEach((f) => {
      const reader = new FileReader();
      reader.onload = () =>
        setExtraRefs((prev) => [...prev, reader.result as string]);
      reader.readAsDataURL(f);
    });
  };

  const handleConfirm = async () => {
    if (!prompt.trim()) return;
    await onGenerate({ prompt: prompt.trim(), model, referenceImages: allRefImages });
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
            完善视频 — 分镜 {scene.sceneNumber}
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
        <div style={{ padding: '16px 20px', display: 'flex', 'flexDirection': 'column', gap: 14 }}>

          {/* Current image / video preview */}
          <div style={{ display: 'flex', gap: 10 }}>
            {hasCurrentImage && (
              <div style={{ flex: 1 }}>
                <span style={labelStyle}>当前图片</span>
                <img
                  src={assetUrl(scene.imageUrl)}
                  alt={`分镜 ${scene.sceneNumber} 图片`}
                  style={{
                    width: '100%',
                    maxHeight: 140,
                    objectFit: 'contain',
                    borderRadius: 'var(--rounded-sm)',
                    border: '1px solid var(--color-hairline)',
                    background: '#f5f5f5',
                  }}
                />
              </div>
            )}
            {hasCurrentVideo && (
              <div style={{ flex: 1 }}>
                <span style={labelStyle}>当前视频预览</span>
                <video
                  src={assetUrl(scene.videoUrl)}
                  controls
                  muted
                  style={{
                    width: '100%',
                    maxHeight: 140,
                    borderRadius: 'var(--rounded-sm)',
                    border: '1px solid var(--color-hairline)',
                    background: '#000',
                  }}
                />
              </div>
            )}
          </div>

          {!hasCurrentImage && !hasCurrentVideo && (
            <div
              style={{
                fontSize: 12,
                color: 'var(--color-muted)',
                textAlign: 'center',
                padding: '16px 0',
                background: 'var(--color-surface-card)',
                borderRadius: 'var(--rounded-sm)',
              }}
            >
              尚未生成图片或视频，请先完善图片
            </div>
          )}

          {/* Prompt */}
          <div>
            <span style={labelStyle}>生视频提示词</span>
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={4}
              style={{
                ...inputBase,
                resize: 'vertical',
                minHeight: 72,
              }}
              placeholder="输入生视频提示词…"
            />
          </div>

          {/* Extra reference images */}
          {maxExtra > 0 && (
            <div>
              <span style={labelStyle}>
                额外参考图（最多{maxExtra}张，已选{extraRefs.length}/{maxExtra}）— 当前图片将自动作为参考
              </span>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: extraRefs.length > 0 ? 8 : 0 }}>
                {extraRefs.map((url, i) => (
                  <div key={i} style={{ position: 'relative' }}>
                    <img
                      src={url}
                      style={{
                        width: 56,
                        height: 56,
                        borderRadius: 4,
                        objectFit: 'cover',
                        border: '1px solid var(--color-hairline)',
                      }}
                    />
                    <span
                      onClick={() =>
                        setExtraRefs((prev) => prev.filter((_, j) => j !== i))
                      }
                      style={{
                        position: 'absolute',
                        top: -6,
                        right: -6,
                        background: 'var(--color-error)',
                        color: 'white',
                        borderRadius: '50%',
                        width: 18,
                        height: 18,
                        fontSize: 11,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        cursor: 'pointer',
                        lineHeight: 1,
                      }}
                    >
                      ×
                    </span>
                  </div>
                ))}
              </div>
              {extraRefs.length < maxExtra && (
                <button
                  onClick={() => fileRef.current?.click()}
                  style={{
                    padding: '5px 12px',
                    fontSize: 11,
                    borderRadius: 'var(--rounded-sm)',
                    border: '1px dashed var(--color-hairline)',
                    background: 'white',
                    cursor: 'pointer',
                    color: 'var(--color-muted)',
                  }}
                >
                  + 上传参考图
                </button>
              )}
              <input
                ref={fileRef}
                type="file"
                accept="image/*"
                multiple
                hidden
                onChange={handleUpload}
              />
            </div>
          )}

          {/* Reference summary */}
          <div
            style={{
              fontSize: 10,
              color: 'var(--color-muted)',
              background: 'var(--color-surface-card)',
              padding: '6px 10px',
              borderRadius: 'var(--rounded-sm)',
            }}
          >
            📎 参考图共 {totalRefs} 张
            {hasCurrentImage ? '（含当前生成图）' : ''}
            {extraRefs.length > 0 ? ` + ${extraRefs.length} 张额外上传` : ''}
          </div>

          {/* Model selector */}
          <div>
            <span style={labelStyle}>生成模型</span>
            <select
              value={model}
              onChange={(e) => setModel(e.target.value)}
              style={selectStyle}
            >
              {VIDEO_MODELS.map((m) => (
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
