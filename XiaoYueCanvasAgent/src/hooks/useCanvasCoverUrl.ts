import { useEffect, useState } from 'react';
import { useObjectUrl } from './useObjectUrl';
import { resolveCanvasCoverBlob } from '../services/canvasCover';

/** 画布封面 objectURL：canvasId + 后端封面资产 id → Blob → objectURL。 */
export function useCanvasCoverUrl(canvasId: string, coverAssetId?: string | null): string | null {
  const [blob, setBlob] = useState<Blob | null>(null);

  useEffect(() => {
    let cancelled = false;
    setBlob(null);
    void resolveCanvasCoverBlob(canvasId, coverAssetId).then((next) => {
      if (!cancelled) setBlob(next);
    });
    return () => { cancelled = true; };
  }, [canvasId, coverAssetId]);

  return useObjectUrl(blob ?? undefined);
}
