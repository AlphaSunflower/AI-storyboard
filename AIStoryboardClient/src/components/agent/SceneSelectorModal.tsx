import { useState } from 'react';
import { createPortal } from 'react-dom';
import { useProjectStore } from '../../stores/projectStore';
import type { SceneResponse } from '../../api/projects';
import { assetUrl } from '../../config';

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirm: (scenes: SceneResponse[]) => void;
}

type Filter = 'all' | 'no-image' | 'no-video';

const FILTER_LABELS: Record<Filter, string> = {
  'all': '全部',
  'no-image': '未生图',
  'no-video': '未生视频',
};

/**
 * 分镜选择弹窗：展示当前项目分镜，支持按生成状态筛选、勾选后发送给 Moon 智能体分析。
 * portal 到 body，避免被抽屉 overflow 裁剪。
 */
export function SceneSelectorModal({ open, onClose, onConfirm }: Props) {
  const scenes = useProjectStore((s) => s.scenes);
  const [filter, setFilter] = useState<Filter>('all');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  if (!open) return null;

  const filtered = scenes.filter((s) => {
    if (filter === 'no-image') return !s.imageUrl;
    if (filter === 'no-video') return !s.videoUrl;
    return true;
  });

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  const toggleAll = () => {
    if (selected.size === filtered.length) setSelected(new Set());
    else setSelected(new Set(filtered.map((s) => s.id)));
  };

  const handleConfirm = () => {
    const picked = scenes.filter((s) => selected.has(s.id));
    if (picked.length === 0) return;
    onConfirm(picked);
    setSelected(new Set());
  };

  return createPortal(
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        background: 'rgba(0,0,0,0.35)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 520, maxHeight: '72vh', display: 'flex', flexDirection: 'column',
          background: 'white', borderRadius: 16,
          boxShadow: '0 8px 32px rgba(0,0,0,0.18)',
          overflow: 'hidden',
        }}
      >
        {/* 顶部 */}
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--color-hairline)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: 16, fontWeight: 600, color: 'var(--color-ink)' }}>选择分镜</span>
          <button onClick={onClose} style={{ border: 'none', background: 'none', fontSize: 18, cursor: 'pointer', color: 'var(--color-muted)', padding: '0 4px' }}>✕</button>
        </div>

        {/* 筛选 tab */}
        <div style={{ padding: '10px 20px', display: 'flex', gap: 8, borderBottom: '1px solid var(--color-hairline)' }}>
          {(Object.keys(FILTER_LABELS) as Filter[]).map((f) => (
            <button
              key={f}
              onClick={() => { setFilter(f); setSelected(new Set()); }}
              style={{
                padding: '5px 14px', borderRadius: 8, border: 'none',
                background: filter === f ? 'var(--color-primary)' : 'var(--color-canvas)',
                color: filter === f ? 'white' : 'var(--color-muted)',
                fontSize: 13, cursor: 'pointer', fontWeight: filter === f ? 600 : 400,
              }}
            >
              {FILTER_LABELS[f]}
              {f === 'no-image' && ` (${scenes.filter((s) => !s.imageUrl).length})`}
              {f === 'no-video' && ` (${scenes.filter((s) => !s.videoUrl).length})`}
            </button>
          ))}
        </div>

        {/* 全选 */}
        {filtered.length > 0 && (
          <label style={{ padding: '8px 20px', display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--color-muted)', cursor: 'pointer', borderBottom: '1px solid var(--color-hairline)' }}>
            <input
              type="checkbox"
              checked={selected.size === filtered.length && filtered.length > 0}
              onChange={toggleAll}
              style={{ accentColor: 'var(--color-primary)' }}
            />
            全选（{filtered.length} 个）
          </label>
        )}

        {/* 分镜列表 */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
          {filtered.length === 0 && (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-muted)', fontSize: 14 }}>
              没有符合条件的分镜
            </div>
          )}
          {filtered.map((s) => (
            <label
              key={s.id}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '8px 20px', cursor: 'pointer',
                background: selected.has(s.id) ? 'var(--color-primary-soft, #fdf1ec)' : 'transparent',
              }}
            >
              <input
                type="checkbox"
                checked={selected.has(s.id)}
                onChange={() => toggle(s.id)}
                style={{ accentColor: 'var(--color-primary)', flexShrink: 0 }}
              />
              {/* 缩略图 */}
              {s.imageUrl ? (
                <img
                  src={assetUrl(s.imageUrl)}
                  style={{ width: 40, height: 40, borderRadius: 8, objectFit: 'cover', border: '1px solid var(--color-hairline)', flexShrink: 0 }}
                />
              ) : (
                <div style={{ width: 40, height: 40, borderRadius: 8, background: 'var(--color-canvas)', border: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, color: 'var(--color-muted)', flexShrink: 0 }}>
                  {s.sceneNumber}
                </div>
              )}
              {/* 信息 */}
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--color-ink)', marginBottom: 2 }}>
                  分镜 {s.sceneNumber}
                </div>
                <div style={{ fontSize: 12, color: 'var(--color-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {s.scriptContent?.slice(0, 50) || '无描述'}
                </div>
              </div>
              {/* 状态标签 */}
              <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
                <StatusTag ok={!!s.imageUrl} label="图" />
                <StatusTag ok={!!s.videoUrl} label="视频" />
              </div>
            </label>
          ))}
        </div>

        {/* 底部 */}
        <div style={{ padding: '12px 20px', borderTop: '1px solid var(--color-hairline)', display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
          <button
            onClick={onClose}
            style={{ padding: '8px 18px', border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'white', color: 'var(--color-muted)', fontSize: 14, cursor: 'pointer' }}
          >
            取消
          </button>
          <button
            disabled={selected.size === 0}
            onClick={handleConfirm}
            style={{
              padding: '8px 18px', border: 'none', borderRadius: 8,
              background: 'var(--color-primary)', color: 'white', fontSize: 14,
              cursor: selected.size === 0 ? 'not-allowed' : 'pointer',
              opacity: selected.size === 0 ? 0.5 : 1,
            }}
          >
            发送给 Moon 智能体（{selected.size}）
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

function StatusTag({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span style={{
      fontSize: 11, padding: '1px 6px', borderRadius: 4,
      background: ok ? '#e6f7e6' : '#f5f5f5',
      color: ok ? '#389e38' : '#999',
    }}>
      {ok ? `有${label}` : `无${label}`}
    </span>
  );
}
