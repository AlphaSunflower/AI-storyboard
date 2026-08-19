import { useState } from 'react';
import { useAgentStore } from '../stores/agentStore';
import { assetUrl } from '../config';
import { ImagePreviewModal } from './ImagePreviewModal';

/** 生成完成后的看图确认卡片(后端 confirm_result 事件) */
export function ConfirmResultCard() {
  const info = useAgentStore((s) => s.confirmResult);
  const refineAsset = useAgentStore((s) => s.refineAsset);
  const dismissConfirm = useAgentStore((s) => s.dismissConfirm);
  const streaming = useAgentStore((s) => s.streaming);
  // 图片点击放大预览(灯箱)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  if (!info) return null;

  const isScript = info.kind === 'script';
  const isVideo = info.kind === 'video';
  // 图片结果:多图(urls)优先,否则回退单 url
  const imgs = !isVideo && !isScript
    ? (info.urls && info.urls.length ? info.urls : (info.url ? [info.url] : []))
    : [];
  return (
    <div className="flex justify-start mb-7">
      <div className="w-full text-left" style={{
        padding: 26, borderRadius: 18,
        background: 'white', border: '1px solid var(--color-border)',
        boxShadow: '0 1px 6px rgba(20,20,19,0.04)',
      }}>
        <div className="text-[15px] font-semibold mb-3" style={{ color: 'var(--color-muted)' }}>
          {isScript ? '分镜生成完成' : isVideo ? '视频生成完成' : '图片生成完成'}
        </div>
        {isVideo ? (
          info.url && (
            <video
              src={assetUrl(info.url)}
              controls
              style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 10, margin: '4px 0 10px', display: 'block' }}
            />
          )
        ) : !isScript ? (
          <div className="flex flex-wrap gap-2.5" style={{ margin: '4px 0 10px' }}>
            {imgs.map((u, i) => (
              <img
                key={i}
                src={assetUrl(u)}
                alt="生成结果"
                onClick={() => setPreviewUrl(u)}
                style={{ maxWidth: '100%', maxHeight: 200, borderRadius: 10, display: 'block', cursor: 'zoom-in' }}
                onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
              />
            ))}
          </div>
        ) : null}
        {!isScript && <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />}
        {isScript && (
          <div style={{ fontSize: 16, color: 'var(--color-ink)', lineHeight: 1.7, marginBottom: 10 }}>
            {typeof info.sceneCount === 'number' && info.sceneCount > 0
              ? `已生成 ${info.sceneCount} 个分镜，请查看左侧分镜列表`
              : '分镜已确认，请查看左侧分镜列表'}
          </div>
        )}
        {!isScript && (
          <div className="flex gap-3">
            <button
              disabled={streaming}
              onClick={() => refineAsset()}
              style={{
                padding: '13px 20px', borderRadius: 12, fontSize: 16, fontWeight: 500,
                background: 'var(--color-primary)', color: 'var(--color-on-primary)', border: 'none',
                cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
              }}
            >
              继续完善
            </button>
            <button
              disabled={streaming}
              onClick={() => void dismissConfirm()}
              style={{
                padding: '13px 20px', borderRadius: 12, fontSize: 16,
                border: '1px solid var(--color-border)', background: 'white', color: 'var(--color-muted)',
                cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
              }}
            >
              满意完成
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
