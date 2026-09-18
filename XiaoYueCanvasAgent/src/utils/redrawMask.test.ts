import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RedrawMaskDocument } from '../types';
import {
  buildRedrawPrompt,
  hasDrawableRedrawMask,
  rasterizeRedrawMask,
  redrawPaintMarks,
  validateRedrawDocument,
} from './redrawMask';

const mask = (marks: RedrawMaskDocument['marks']): RedrawMaskDocument => ({
  version: 2,
  sourceAssetId: 'source',
  sourceWidth: 1000,
  sourceHeight: 1000,
  marks,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('redraw mask document', () => {
  it('accepts paint regions and ignores eraser-only documents', () => {
    expect(hasDrawableRedrawMask(mask([
      { id: 'erase', type: 'brush', operation: 'erase', points: [{ x: .2, y: .2 }], width: .03 },
    ]))).toBe(false);
    expect(hasDrawableRedrawMask(mask([
      { id: 'paint', type: 'rectangle', operation: 'paint', start: { x: .1, y: .2 }, end: { x: .5, y: .7 }, intent: '改成蓝色织物', color: '#59d8ff', createdAt: 1 },
    ]))).toBe(true);
  });

  it('keeps region order and concatenates one numbered prompt', () => {
    const document = mask([
      { id: 'later', type: 'rectangle', operation: 'paint', start: { x: .6, y: .2 }, end: { x: .8, y: .5 }, intent: '把墙面调暖', color: '#f59e0b', createdAt: 2 },
      { id: 'earlier', type: 'brush', operation: 'paint', points: [{ x: .2, y: .3 }, { x: .3, y: .35 }], width: .04, intent: '给椅子换布', color: '#d7ff54', createdAt: 1 },
      { id: 'erase', type: 'brush', operation: 'erase', points: [{ x: .4, y: .4 }], width: .02 },
    ]);

    expect(redrawPaintMarks(document).map((mark) => mark.id)).toEqual(['earlier', 'later']);
    expect(buildRedrawPrompt(document)).toBe([
      '请处理这张图：房间内黄绿色涂抹位置一按要求处理：给椅子换布；橙色框选位置二按要求处理：把墙面调暖。不要改变其他布局，确保修改内容与整张图的风格一致。',
    ].join('\n'));
  });

  it('rejects a region without an intent', () => {
    expect(() => validateRedrawDocument(mask([
      { id: 'paint', type: 'rectangle', operation: 'paint', start: { x: .1, y: .2 }, end: { x: .5, y: .7 }, color: '#d7ff54', createdAt: 1 },
    ]))).toThrow('请为每个选区填写修改意图');
  });

  it('rasterizes editable regions with transparent pixels', async () => {
    const operations: string[] = [];
    class FakeContext {
      globalCompositeOperation = 'source-over';
      fillStyle = '';
      strokeStyle = '';
      lineWidth = 1;
      lineCap = '';
      lineJoin = '';
      save() {}
      restore() {}
      fillRect() { operations.push(this.globalCompositeOperation); }
      beginPath() {}
      arc() {}
      fill() { operations.push(this.globalCompositeOperation); }
      moveTo() {}
      lineTo() {}
      stroke() { operations.push(this.globalCompositeOperation); }
      getImageData() { return { data: new Uint8ClampedArray([0, 0, 0, 0]) }; }
    }
    class FakeOffscreenCanvas {
      constructor(public width: number, public height: number) {}
      getContext() { return new FakeContext(); }
      async convertToBlob() { return new Blob(['mask'], { type: 'image/png' }); }
    }
    vi.stubGlobal('OffscreenCanvas', FakeOffscreenCanvas);

    const blob = await rasterizeRedrawMask(mask([
      { id: 'paint', type: 'rectangle', operation: 'paint', start: { x: .1, y: .2 }, end: { x: .5, y: .7 }, intent: '改成蓝色', color: '#59d8ff', createdAt: 1 },
    ]), 64, 48);

    expect(blob.type).toBe('image/png');
    expect(operations).toContain('destination-out');
  });
});
