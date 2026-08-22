import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';
import { Folder } from 'lucide-react';

/**
 * 当前对话的产出素材弹窗（文件夹图标入口，不再常驻底部）：
 * - 打开时刷新素材列表（loadAssets）
 * - 网格缩略图 + 删除 + 分页；点击图片可预览大图
 * - 遮罩点击 / ✕ / ESC 关闭
 */
export function AgentAssetsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { assets, loadAssets, deleteAsset } = useAgentStore();
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const gridRef = useRef<HTMLDivElement>(null);

  // B6: 素材网格入场——打开/翻页时缩略图 stagger 浮现
  useGSAP(() => {
    const grid = gridRef.current;
    if (!grid || (assets?.records?.length ?? 0) === 0) return;
    const tiles = Array.from(grid.children);
    gsap.from(tiles, {
      y: 12,
      opacity: 0,
      scale: 0.96,
      duration: 0.3,
      ease: 'power2.out',
      stagger: 0.03,
      onComplete: () => {
        // 清除残留 transform：网格项含 fixed 灯箱场景（点击预览），需清理避免 containing block
        tiles.forEach((el) => gsap.set(el, { clearProps: 'transform' }));
      },
    });
  }, { dependencies: [open, assets?.records?.length, assets?.page], scope: gridRef });

  // 打开时刷新素材列表
  useEffect(() => {
    if (open) void loadAssets();
  }, [open, loadAssets]);

  // ESC 关闭
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  const records = assets?.records ?? [];
  const total = assets?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / (assets?.size ?? 20)));

  return (
    <>
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, zIndex: 300,
          background: 'rgba(20, 20, 19, 0.45)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
        }}
      >
        <div
          onClick={(e) => e.stopPropagation()}
          style={{
            width: 620, maxWidth: '94vw', maxHeight: '80vh',
            background: 'white', borderRadius: 14, boxShadow: '0 12px 48px rgba(0,0,0,0.22)',
            display: 'flex', flexDirection: 'column', overflow: 'hidden',
          }}
        >
          {/* 头部 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '18px 22px', borderBottom: '1px solid var(--color-hairline)' }}>
            <span style={{ fontSize: 17, fontWeight: 600, color: 'var(--color-ink)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Folder size={15} strokeWidth={1.8} /> 产出素材（{total}）
            </span>
            <button
              onClick={onClose}
              aria-label="关闭素材面板"
              style={{ width: 34, height: 34, border: 'none', borderRadius: '50%', background: 'var(--color-surface-soft)', color: 'var(--color-muted)', cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
            >
              ✕
            </button>
          </div>
          {/* 网格 */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 18 }}>
            {records.length === 0 ? (
              <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
                暂无产出素材——生成的图片/视频会出现在这里
              </p>
            ) : (
              <div ref={gridRef} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(84px, 1fr))', gap: 10 }}>
                {records.map((a) => (
                  <div
                    key={a.id}
                    onClick={() => { if (a.type !== 'video') setPreviewUrl(a.url); }}
                    title={a.type === 'video' ? '视频' : '点击预览大图'}
                    style={{
                      position: 'relative', aspectRatio: '1', borderRadius: 10, overflow: 'hidden',
                      background: 'var(--color-surface-soft)',
                      cursor: a.type === 'video' ? 'default' : 'zoom-in',
                    }}
                  >
                    {a.type === 'video' ? (
                      <video src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
                    ) : (
                      <img src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                    )}
                    <button
                      onClick={(e) => { e.stopPropagation(); if (window.confirm('删除该素材？')) void deleteAsset(a.id); }}
                      title="删除"
                      style={{
                        position: 'absolute', top: 4, right: 4, width: 18, height: 18,
                        border: 'none', borderRadius: '50%', background: 'rgba(198, 69, 69, 0.9)',
                        color: 'white', fontSize: 10, cursor: 'pointer', lineHeight: 1,
                      }}
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
          {/* 分页 */}
          {totalPages > 1 && (
            <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0 12px', borderTop: '1px solid var(--color-hairline)' }}>
              <button disabled={(assets?.page ?? 1) <= 1} onClick={() => void loadAssets((assets?.page ?? 1) - 1)} style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}>上一页</button>
              <span style={{ fontSize: 13, color: 'var(--color-muted)', alignSelf: 'center' }}>{assets?.page ?? 1} / {totalPages}</span>
              <button disabled={(assets?.page ?? 1) >= totalPages} onClick={() => void loadAssets((assets?.page ?? 1) + 1)} style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}>下一页</button>
            </div>
          )}
        </div>
      </div>
      {/* 缩略图点击预览大图 */}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </>
  );
}
