import { describe, expect, it } from 'vitest';
import { mediaQualityForZoom } from './canvasPerformance';

describe('canvas performance policy', () => {
  it('uses hysteresis so small zoom fluctuations do not swap media variants', () => {
    expect(mediaQualityForZoom(.6, 'thumbnail')).toBe('thumbnail');
    expect(mediaQualityForZoom(.63, 'thumbnail')).toBe('preview');
    expect(mediaQualityForZoom(.5, 'preview')).toBe('preview');
    expect(mediaQualityForZoom(.47, 'preview')).toBe('thumbnail');
  });
});
