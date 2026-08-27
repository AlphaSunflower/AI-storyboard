import type { MediaQuality } from '../types';

export function mediaQualityForZoom(zoom: number, previous: MediaQuality): MediaQuality {
  if (previous === 'placeholder') return zoom > .26 ? 'thumbnail' : 'placeholder';
  if (previous === 'preview') return zoom < .48 ? 'thumbnail' : 'preview';
  if (zoom < .20) return 'placeholder';
  return zoom > .62 ? 'preview' : 'thumbnail';
}
