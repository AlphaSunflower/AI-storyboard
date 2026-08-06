import { useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';

/** 生成完成后的看图确认卡片（后端 confirm_result 事件） */
export function ConfirmResultCard() {
  const info = useAgentStore((s) => s.confirmResult);
  const refineAsset = useAgentStore((s) => s.refineAsset);
  const dismissConfirm = useAgentStore((s) => s.dismissConfirm);
  const streaming = useAgentStore((s) => s.streaming);
  // 图片点击放大预览（灯箱）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  if (!info) return null;

  const isScript = info.kind === 'script';
  const isVideo = info.kind === 'video';
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div
        style={{
          maxWidth: '82%', padding: 12, borderRadius: 12,
          background: 'white', border: '1px solid var(--color-hairline)',
          boxShadow: '0 2px 8px rgba(20,20,19,0.06)', textAlign: 'left',
        }}
      >
        <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>
          {isScript ? '分镜生成完成' : isVideo ? '视频生成完成' : '图片生成完成'}
        </div>
        {info.url &&
          (isVideo ? (
            <video
              src={assetUrl(info.url)}
              controls
              style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 8, margin: '4px 0 8px', display: 'block' }}
            />
          ) : (
            <img
              src={assetUrl(info.url)}
              alt="生成结果"
              onClick={() => setPreviewUrl(info.url)}
              style={{ maxWidth: '100%', maxHeight: 200, borderRadius: 8, margin: '4px 0 8px', display: 'block', cursor: 'zoom-in' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          ))}
        {!isScript && <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />}
        {isScript && (
          <div style={{ fontSize: 13, color: 'var(--color-ink)', lineHeight: 1.6, marginBottom: 8 }}>
            {typeof info.sceneCount === 'number' && info.sceneCount > 0
              ? `已生成 ${info.sceneCount} 个分镜，请查看左侧分镜列表`
              : '分镜已确认，请查看左侧分镜列表'}
          </div>
        )}
        {!isScript && (
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              disabled={streaming}
              onClick={() => refineAsset()}
              style={{
                padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                background: 'var(--color-primary)', color: 'white', fontSize: 13,
                cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
              }}
            >
              继续完善
            </button>
            <button
              disabled={streaming}
              onClick={() => dismissConfirm()}
              style={{
                padding: '6px 16px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
                background: 'white', color: 'var(--color-muted)', fontSize: 13,
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
