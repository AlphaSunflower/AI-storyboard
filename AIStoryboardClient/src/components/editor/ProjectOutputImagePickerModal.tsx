import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, FolderOpen, Image as ImageIcon, X } from 'lucide-react';
import { agentApi, type ProjectOutputMaterialGroup } from '../../api/agent';
import type { SceneMaterialReferenceInput } from '../../api/assets';
import { assetUrl } from '../../config';

interface Props {
  open: boolean;
  projectId?: string;
  mediaType?: 'image' | 'video' | 'audio';
  existingUrls: string[];
  remaining: number;
  onClose: () => void;
  onConfirm: (references: SceneMaterialReferenceInput[]) => Promise<void>;
}

export function ProjectOutputImagePickerModal({
  open,
  projectId,
  mediaType = 'image',
  existingUrls,
  remaining,
  onClose,
  onConfirm,
}: Props) {
  const [groups, setGroups] = useState<ProjectOutputMaterialGroup[]>([]);
  const [selected, setSelected] = useState<Map<string, SceneMaterialReferenceInput>>(new Map());
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setSelected(new Map());
    setError('');
    if (!projectId) {
      setGroups([]);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    agentApi.listProjectOutputMaterials(projectId)
      .then((res) => {
        if (!cancelled) setGroups(res.data.data ?? []);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载项目产出素材失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [open, projectId]);

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !saving) onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose, saving]);

  const existing = useMemo(() => new Set(existingUrls), [existingUrls]);
  const visibleGroups = useMemo(() => {
    const seenUrls = new Set<string>();
    return groups
      .map((group) => {
        const items = group.items
          .filter((item) => item.type === mediaType)
          .map((item) => ({
            ...item,
            urls: item.urls.filter((url) => {
              if (seenUrls.has(url)) return false;
              seenUrls.add(url);
              return true;
            }),
          }))
          .filter((item) => item.urls.length > 0);
        return { ...group, items };
      })
      .filter((group) => group.items.length > 0);
  }, [groups, mediaType]);

  if (!open) return null;

  const toggle = (reference: SceneMaterialReferenceInput) => {
    if (existing.has(reference.url)) return;
    setError('');
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(reference.url)) {
        next.delete(reference.url);
      } else if (next.size < remaining) {
        next.set(reference.url, reference);
      } else {
        setError(`最多还能添加 ${remaining} 张参考图`);
      }
      return next;
    });
  };

  const confirm = async () => {
    if (selected.size === 0) return;
    setSaving(true);
    setError('');
    try {
      await onConfirm([...selected.values()]);
      onClose();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '添加参考图失败');
    } finally {
      setSaving(false);
    }
  };

  return createPortal(
    <div style={overlay} onPointerDown={(event) => { if (event.target === event.currentTarget && !saving) onClose(); }}>
      <div style={dialog} onPointerDown={(event) => event.stopPropagation()}>
        <header style={header}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--color-ink)' }}>
              从项目产出素材添加{mediaType === 'image' ? '参考图' : mediaType === 'video' ? '参考视频' : '参考音频'}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-muted)', marginTop: 3 }}>
              仅显示项目内已生成完成的{mediaType === 'image' ? '图片' : mediaType === 'video' ? '视频' : '音频'}，已添加的内容不可重复选择
            </div>
          </div>
          <button type="button" onClick={onClose} disabled={saving} style={iconButton} aria-label="关闭">
            <X size={17} />
          </button>
        </header>

        <div style={body}>
          {!projectId ? (
            <Empty icon={<FolderOpen size={20} />} text="请先进入一个项目" />
          ) : loading ? (
            <>
              <style>{'@keyframes output-picker-pulse { 0%, 100% { opacity: .48; } 50% { opacity: .9; } }'}</style>
              <div style={grid}>
                {Array.from({ length: 6 }, (_, index) => (
                  <div key={index} style={skeletonTile} />
                ))}
              </div>
            </>
          ) : visibleGroups.length === 0 ? (
            <Empty icon={<ImageIcon size={20} />} text={`当前项目暂无生成${mediaType === 'image' ? '图片' : mediaType === 'video' ? '视频' : '音频'}`} />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
              {visibleGroups.map((group) => (
                <section key={group.conversationId}>
                  <div style={groupTitle}>{group.title || '未命名对话'}</div>
                  <div style={grid}>
                    {group.items.flatMap((item) => item.urls.map((url) => {
                      const reference: SceneMaterialReferenceInput = {
                        type: mediaType,
                        purpose: mediaType === 'image' ? 'image' : 'video',
                        url,
                        role: 'REFERENCE',
                        sourceType: 'output',
                        sourceId: item.id,
                        sourceConversationId: group.conversationId,
                      };
                      const alreadyAdded = existing.has(url);
                      const active = selected.has(url);
                      return (
                        <button
                          key={`${item.id}-${url}`}
                          type="button"
                          onClick={() => toggle(reference)}
                          disabled={alreadyAdded || remaining <= 0}
                          aria-pressed={active}
                          style={tileButton(active, alreadyAdded || remaining <= 0)}
                        >
                          <img src={assetUrl(url)} alt="" style={media} />
                          {(active || alreadyAdded) && (
                            <span style={checkBadge}>
                              {alreadyAdded ? '已添加' : <Check size={12} />}
                            </span>
                          )}
                        </button>
                      );
                    }))}
                  </div>
                </section>
              ))}
            </div>
          )}
        </div>

        <footer style={footer}>
            <span style={{ fontSize: 12, color: error ? 'var(--color-error)' : 'var(--color-muted)' }}>
            {error || (remaining > 0
              ? `最多可选择 ${remaining} 张，已选 ${selected.size} 张`
              : '当前分镜参考素材数量已达上限')}
          </span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" onClick={onClose} disabled={saving} style={ghostButton}>取消</button>
            <button
              type="button"
              onClick={() => void confirm()}
              disabled={saving || selected.size === 0}
              style={primaryButton(selected.size === 0)}
            >
              {saving ? '添加中...' : `添加 ${selected.size || ''} 项`}
            </button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}

function Empty({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div style={empty}>
      <span style={{ color: 'var(--color-muted-soft)' }}>{icon}</span>
      <span>{text}</span>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 1450,
  padding: 16,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'rgba(15,17,21,0.46)',
};
const dialog: React.CSSProperties = {
  width: '100%',
  maxWidth: 760,
  maxHeight: 'min(82dvh, 760px)',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  borderRadius: 16,
  background: 'var(--color-surface-card)',
  boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  borderBottom: '1px solid var(--color-hairline)',
};
const body: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: 16,
};
const footer: React.CSSProperties = {
  padding: '12px 18px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 12,
  borderTop: '1px solid var(--color-hairline)',
};
const grid: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(112px, 1fr))',
  gap: 10,
};
const media: React.CSSProperties = {
  width: '100%',
  aspectRatio: '1',
  display: 'block',
  objectFit: 'cover',
  background: 'var(--color-surface-soft)',
};
const empty: React.CSSProperties = {
  minHeight: 190,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 8,
  color: 'var(--color-muted)',
  fontSize: 13,
};
const groupTitle: React.CSSProperties = {
  margin: '0 0 8px',
  color: 'var(--color-muted)',
  fontSize: 12,
  fontWeight: 600,
};
const skeletonTile: React.CSSProperties = {
  aspectRatio: '1',
  borderRadius: 10,
  background: 'var(--color-surface-soft)',
  animation: 'output-picker-pulse 1.4s ease-in-out infinite',
};
const checkBadge: React.CSSProperties = {
  position: 'absolute',
  top: 6,
  right: 6,
  minWidth: 20,
  height: 20,
  padding: '0 6px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: 999,
  background: 'var(--color-primary)',
  color: 'var(--color-on-primary)',
  fontSize: 10,
};
const iconButton: React.CSSProperties = {
  padding: 4,
  border: 'none',
  background: 'transparent',
  color: 'var(--color-muted)',
  cursor: 'pointer',
};
const ghostButton: React.CSSProperties = {
  padding: '8px 16px',
  border: '1px solid var(--color-hairline)',
  borderRadius: 10,
  background: 'var(--color-surface-card)',
  color: 'var(--color-body)',
  fontSize: 13,
  cursor: 'pointer',
};
const tileButton = (active: boolean, disabled: boolean): React.CSSProperties => ({
  position: 'relative',
  padding: 0,
  overflow: 'hidden',
  border: `1.5px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  borderRadius: 10,
  background: active ? 'rgba(204,120,92,0.08)' : 'var(--color-surface-card)',
  cursor: disabled ? 'not-allowed' : 'pointer',
  opacity: disabled && !active ? 0.58 : 1,
});
const primaryButton = (disabled: boolean): React.CSSProperties => ({
  padding: '8px 16px',
  border: 'none',
  borderRadius: 10,
  background: disabled ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
  color: disabled ? 'var(--color-muted)' : 'var(--color-on-primary)',
  fontSize: 13,
  cursor: disabled ? 'not-allowed' : 'pointer',
});
