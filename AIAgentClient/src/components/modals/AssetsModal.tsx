import { useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

export function AssetsModal() {
  const { assets, loadAssets, deleteAsset, activeConversationId } = useAgentStore();

  useEffect(() => {
    if (activeConversationId) loadAssets();
  }, [activeConversationId, loadAssets]);

  if (!activeConversationId) {
    return <p className="text-sm" style={{ color: 'var(--color-muted)' }}>请先选择一个对话</p>;
  }

  if (!assets || assets.records.length === 0) {
    return <p className="text-sm" style={{ color: 'var(--color-muted)' }}>暂无资产</p>;
  }

  const totalPages = Math.ceil(assets.total / assets.size);

  return (
    <div>
      <div className="grid grid-cols-2 gap-2 mb-4">
        {assets.records.map((a) => (
          <div
            key={a.id}
            className="rounded-lg overflow-hidden relative group"
            style={{ border: '1px solid var(--color-hairline)' }}
          >
            {a.type === 'video' ? (
              <video
                src={assetUrl(a.url)}
                className="w-full aspect-square object-cover"
                muted
                onMouseEnter={(e) => (e.target as HTMLVideoElement).play()}
                onMouseLeave={(e) => { (e.target as HTMLVideoElement).pause(); (e.target as HTMLVideoElement).currentTime = 0; }}
              />
            ) : (
              <img src={assetUrl(a.url)} alt="" className="w-full aspect-square object-cover" />
            )}
            <div className="absolute bottom-0 left-0 right-0 px-2 py-1 text-xs flex justify-between items-center"
              style={{ background: 'rgba(255,255,255,0.85)', color: 'var(--color-muted)' }}
            >
              <span>{a.type}</span>
              <button
                onClick={() => deleteAsset(a.id)}
                className="opacity-0 group-hover:opacity-100 transition-opacity"
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#c0392b', fontSize: 11 }}
              >
                删除
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 text-xs" style={{ color: 'var(--color-muted)' }}>
          <button
            disabled={assets.page <= 1}
            onClick={() => loadAssets(assets.page - 1)}
            style={{ background: 'none', border: '1px solid var(--color-hairline)', borderRadius: 4, padding: '2px 8px', cursor: 'pointer' }}
          >
            ‹
          </button>
          <span>{assets.page} / {totalPages}</span>
          <button
            disabled={assets.page >= totalPages}
            onClick={() => loadAssets(assets.page + 1)}
            style={{ background: 'none', border: '1px solid var(--color-hairline)', borderRadius: 4, padding: '2px 8px', cursor: 'pointer' }}
          >
            ›
          </button>
        </div>
      )}
    </div>
  );
}
