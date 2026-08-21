import { useRef, type CSSProperties } from 'react';
import type { SceneReferenceAsset } from '../../api/projects';
import { assetUrl, REFERENCE_LIMITS } from '../../config';

/**
 * 分镜参考素材上传组件（图片/视频/音频通用）：
 * 上传虚线框（accept 按类型）+ 剩余额度提示 + 缩略列表（图片显示缩略图，视频/音频显示文件名与大小）。
 * 前端预检：类型 / 单文件大小 / 数量上限（上限值来自网关 params，未配置用 REFERENCE_LIMITS 兜底）。
 */

interface Props {
  type: 'image' | 'video' | 'audio';
  items: SceneReferenceAsset[];
  /** 上限（来自网关 params；缺省用静态兜底） */
  maxCount?: number;
  maxSizeMB?: number;
  onUpload: (file: File) => void;
  onDelete: (id: string) => void;
  disabled?: boolean;
}

const TYPE_LABEL = { image: '参考图', video: '参考视频', audio: '参考音频' } as const;

const ACCEPT = {
  image: 'image/*',
  video: 'video/mp4,video/quicktime',
  audio: 'audio/wav,audio/mpeg,audio/mp4',
} as const;

function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

export function ReferenceUploader({ type, items, maxCount, maxSizeMB, onUpload, onDelete, disabled }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const limit = REFERENCE_LIMITS[type];
  const count = maxCount ?? limit.maxCount;
  const sizeMB = maxSizeMB ?? limit.maxSizeMB;
  const remain = Math.max(0, count - items.length);

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    e.target.value = ''; // 允许重复选择同一文件
    for (const f of files) {
      if (items.length >= count) { alert(`${TYPE_LABEL[type]}最多 ${count} 个`); break; }
      if (f.size > sizeMB * 1024 * 1024) { alert(`「${f.name}」超过大小限制 ${sizeMB}MB`); continue; }
      onUpload(f);
    }
  };

  const boxStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '6px 10px',
    borderRadius: 'var(--rounded-md)',
    border: '1px dashed var(--color-hairline)',
    cursor: disabled ? 'not-allowed' : 'pointer',
    background: 'var(--color-canvas)',
    fontSize: 11,
    color: 'var(--color-muted)',
    opacity: disabled ? 0.5 : 1,
  };

  return (
    <div>
      <div
        onClick={() => !disabled && inputRef.current?.click()}
        style={boxStyle}
        title={disabled ? '开启「以本分镜图片为首帧」时不可用' : undefined}
      >
        <span style={{ fontSize: 14, display: 'inline-flex', alignItems: 'center' }}>{type === 'image' ? <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg> : type === 'video' ? <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> : <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>}</span>
        <span>
          {items.length > 0 ? `${items.length}/${count} 个` : `添加${TYPE_LABEL[type]}（可选）`}
          {` · 单文件 ≤ ${sizeMB}MB`}
          {remain > 0 ? ` · 还可添加 ${remain} 个` : ' · 已达上限'}
        </span>
      </div>
      <input ref={inputRef} type="file" accept={ACCEPT[type]} hidden multiple onChange={handleFile} disabled={disabled} />
      {items.length > 0 && (
        <div style={{ display: 'flex', gap: 4, marginTop: 6, flexWrap: 'wrap' }}>
          {items.map((it) => (
            <div key={it.id} style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 4, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '3px 6px 3px 3px', background: 'white' }}>
              {it.type === 'image' ? (
                <img src={assetUrl(it.url)} alt="参考图" style={{ width: 32, height: 32, borderRadius: 4, objectFit: 'cover' }} />
              ) : (
                <span style={{ fontSize: 16, padding: '0 6px', display: 'inline-flex', alignItems: 'center' }}>{it.type === 'video' ? <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> : <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>}</span>
              )}
              <span style={{ fontSize: 10, color: 'var(--color-muted)', maxWidth: 90, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {it.fileName || it.url.split('/').pop()}
              </span>
              <span style={{ fontSize: 9, color: 'var(--color-muted-soft)' }}>{formatSize(it.fileSize || 0)}</span>
              <span
                onClick={() => !disabled && onDelete(it.id)}
                title="删除"
                style={{
                  background: 'var(--color-error)', color: 'white', borderRadius: '50%',
                  width: 15, height: 15, fontSize: 9, display: 'flex', alignItems: 'center',
                  justifyContent: 'center', cursor: disabled ? 'not-allowed' : 'pointer', flexShrink: 0,
                }}
              >
                ×
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
