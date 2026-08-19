import { useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

export function AssetsContent() {
  const { assets, loadAssets, deleteAsset } = useAgentStore();
  const records = assets?.records ?? [];
  const page = assets?.page ?? 1;
  const total = assets?.total ?? 0;
  const size = assets?.size ?? 20;
  const totalPages = Math.ceil(total / size);

  useEffect(() => { loadAssets(1); }, [loadAssets]);

  if (!records.length) {
    return (
      <div className="flex items-center justify-center py-12 text-sm" style={{ color: 'var(--color-muted)' }}>
        暂无资产
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        {records.map((a) => (
          <div key={a.id} className="group relative rounded-lg overflow-hidden" style={{ border: '1px solid var(--color-hairline)' }}>
            {a.type === 'video' ? (
              <video src={assetUrl(a.url)} className="w-full aspect-video object-cover" controls muted />
            ) : (
              <img src={assetUrl(a.url)} alt={a.prompt ?? ''} className="w-full aspect-video object-cover" />
            )}
            <button
              onClick={() => deleteAsset(a.id)}
              className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 transition-opacity"
              style={{ background: 'rgba(0,0,0,0.6)', color: '#fff' }}
            >
              ✕
            </button>
          </div>
        ))}
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-1">
          {Array.from({ length: totalPages }, (_, i) => i + 1).map((p) => (
            <button
              key={p}
              onClick={() => loadAssets(p)}
              className="w-8 h-8 rounded-md text-xs font-medium transition-colors"
              style={{
                background: p === page ? 'var(--color-primary)' : 'transparent',
                color: p === page ? '#fff' : 'var(--color-muted)',
              }}
            >
              {p}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
