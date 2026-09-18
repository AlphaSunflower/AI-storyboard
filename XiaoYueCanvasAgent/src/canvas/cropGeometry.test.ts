import { describe, expect, it } from 'vitest';
import {
  MIN_CROP_SIZE,
  cropRectFromParams,
  moveCropRect,
  resizeCropRect,
  scaleCropRect,
  type CropRect,
} from './cropGeometry';

const SOURCE_RATIO = 16 / 9; // 原图宽高比
const inside = (rect: CropRect) => rect.x >= -1e-6 && rect.y >= -1e-6
  && rect.x + rect.width <= 1 + 1e-6 && rect.y + rect.height <= 1 + 1e-6;

describe('cropRectFromParams', () => {
  it('uses the whole image for the source ratio without zoom or pan', () => {
    expect(cropRectFromParams(null, 1, 0, 0, SOURCE_RATIO)).toEqual({ x: 0, y: 0, width: 1, height: 1 });
  });

  it('fits a 1:1 preset inside a 16:9 image and keeps it centered', () => {
    const rect = cropRectFromParams(1, 1, 0, 0, SOURCE_RATIO);
    expect(rect.width).toBeCloseTo(1 / SOURCE_RATIO, 6);
    expect(rect.height).toBeCloseTo(1, 6);
    expect(rect.x).toBeCloseTo((1 - rect.width) / 2, 6);
    expect(rect.y).toBe(0);
  });

  it('keeps the crop inside the image at full pan', () => {
    const rect = cropRectFromParams(1, 2, 100, 100, SOURCE_RATIO);
    expect(inside(rect)).toBe(true);
    expect(rect.x + rect.width).toBeCloseTo(1, 6);
  });
});

describe('moveCropRect', () => {
  it('clamps the crop inside the image', () => {
    const start: CropRect = { x: 0.25, y: 0.25, width: 0.5, height: 0.5 };
    expect(moveCropRect(start, 9, 9)).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 });
    expect(moveCropRect(start, -9, -9)).toEqual({ x: 0, y: 0, width: 0.5, height: 0.5 });
  });
});

describe('resizeCropRect', () => {
  const start: CropRect = { x: 0.2, y: 0.2, width: 0.4, height: 0.4 };

  it('keeps the opposite corner anchored for a free resize', () => {
    const rect = resizeCropRect(start, 3, 0.1, 0.1, SOURCE_RATIO, null);
    expect(rect.x).toBeCloseTo(0.2, 6);
    expect(rect.y).toBeCloseTo(0.2, 6);
    expect(rect.width).toBeCloseTo(0.5, 6);
    expect(rect.height).toBeCloseTo(0.5, 6);
  });

  it('locks the pixel ratio when a preset ratio is active', () => {
    const rect = resizeCropRect(start, 3, 0.1, 0.01, SOURCE_RATIO, 1);
    const pixelRatio = (rect.width * SOURCE_RATIO) / rect.height;
    expect(pixelRatio).toBeCloseTo(1, 4);
    expect(inside(rect)).toBe(true);
  });

  it('never grows past the image edge', () => {
    const rect = resizeCropRect(start, 3, 5, 5, SOURCE_RATIO, null);
    expect(inside(rect)).toBe(true);
    expect(rect.x + rect.width).toBeCloseTo(1, 6);
  });

  it('respects the minimum crop size', () => {
    const rect = resizeCropRect(start, 3, -5, -5, SOURCE_RATIO, null);
    expect(rect.width).toBeCloseTo(MIN_CROP_SIZE, 6);
    expect(rect.height).toBeCloseTo(MIN_CROP_SIZE, 6);
  });
});

describe('scaleCropRect', () => {
  it('zooms around the center and stays inside the image', () => {
    const rect = scaleCropRect({ x: 0.4, y: 0.4, width: 0.2, height: 0.2 }, 2);
    expect(rect.width).toBeCloseTo(0.4, 6);
    expect(rect.x + rect.width / 2).toBeCloseTo(0.5, 6);
    expect(inside(rect)).toBe(true);
  });

  it('clamps to the whole image when scaling out', () => {
    const rect = scaleCropRect({ x: 0.2, y: 0.2, width: 0.6, height: 0.6 }, 8);
    expect(rect).toEqual({ x: 0, y: 0, width: 1, height: 1 });
  });
});
