import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '@/stores/agentStore';
import { assetUrl } from '@/config';
import { Trash2, ChevronLeft, ChevronRight, ImageOff } from 'lucide-react';

export function AssetsModal() {
  const { assets, loadAssets, deleteAsset, activeConversationId } = useAgentStore();
  const gridRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (activeConversationId) loadAssets(); }, [activeConversationId, loadAssets]);

  useGSAP(() => {
    if (!gridRef.current) return;
    const cards = gridRef.current.querySelectorAll('[data-card]');
    gsap.fromTo(cards, { y: 6, opacity: 0 }, { y: 0, opacity: 1, duration: 0.15, stagger: 0.025, ease: 'power2.out' });
  }, { dependencies: [assets?.records.length], scope: gridRef });

  if (!activeConversationId) return (
    <div className="flex flex-col items-center py-10">
      <ImageOff size={28} style={{ color: 'var(--color-muted-soft)' }} />
      <p className="text-[14px] mt-3" style={{ color: 'var(--color-muted)' }}>请先选择一个对话</p>
    </div>
  );
  if (!assets || assets.records.length === 0) return (
    <div className="flex flex-col items-center py-10">
      <ImageOff size={28} style={{ color: 'var(--color-muted-soft)' }} />
      <p className="text-[14px] mt-3" style={{ color: 'var(--color-muted)' }}>暂无素材</p>
    </div>
  );

  const totalPages = Math.ceil(assets.total / assets.size);
  return (
    <div>
      <div ref={gridRef} className="grid grid-cols-2 gap-2.5 mb-4">
        {assets.records.map((a) => (
          <div key={a.id} data-card className="rounded-xl overflow-hidden relative group"
            style={{ border: '1px solid var(--color-border)', background: 'var(--color-surface-soft)' }}>
            {a.type === 'video' ? (
              <video src={assetUrl(a.url)} className="w-full aspect-square object-cover" muted
                onMouseEnter={(e) => (e.target as HTMLVideoElement).play()}
                onMouseLeave={(e) => { (e.target as HTMLVideoElement).pause(); (e.target as HTMLVideoElement).currentTime = 0; }} />
            ) : (
              <img src={assetUrl(a.url)} alt="" className="w-full aspect-square object-cover" />
            )}
            <div className="absolute bottom-0 left-0 right-0 px-2.5 py-1.5 flex justify-between items-center"
              style={{ background: 'rgba(250,249,245,0.88)', backdropFilter: 'blur(4px)' }}>
              <span className="text-[13px] font-medium" style={{ color: 'var(--color-muted)' }}>{a.type === 'reference' ? '参考图' : a.type}</span>
              <button onClick={() => deleteAsset(a.id)}
                className="opacity-0 group-hover:opacity-100 transition-opacity p-1 rounded hover:bg-red-50"
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-error)' }}>
                <Trash2 size={13} />
              </button>
            </div>
          </div>
        ))}
      </div>
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 text-[12px]" style={{ color: 'var(--color-muted)' }}>
          <button disabled={assets.page <= 1} onClick={() => loadAssets(assets.page - 1)}
            className="p-1.5 rounded-lg disabled:opacity-30" style={{ background: 'none', border: '1px solid var(--color-border)', cursor: 'pointer' }}>
            <ChevronLeft size={13} />
          </button>
          <span>{assets.page} / {totalPages}</span>
          <button disabled={assets.page >= totalPages} onClick={() => loadAssets(assets.page + 1)}
            className="p-1.5 rounded-lg disabled:opacity-30" style={{ background: 'none', border: '1px solid var(--color-border)', cursor: 'pointer' }}>
            <ChevronRight size={13} />
          </button>
        </div>
      )}
    </div>
  );
}
