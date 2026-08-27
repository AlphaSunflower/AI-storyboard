import { describe, expect, it } from 'vitest';
import { annotationToSvg, splitImageBounds } from './mediaPipeline';

describe('splitImageBounds', () => {
  it('covers non-divisible images without gaps, overlap, or ordering errors', () => {
    const bounds = splitImageBounds(10, 7, 3, 4);
    expect(bounds).toHaveLength(12);
    expect(bounds.map((item) => [item.row, item.column])).toEqual([
      [1,1],[1,2],[1,3],[1,4],[2,1],[2,2],[2,3],[2,4],[3,1],[3,2],[3,3],[3,4],
    ]);
    const covered = new Set<string>();
    bounds.forEach((item) => {
      for (let y = item.y; y < item.y + item.height; y += 1) for (let x = item.x; x < item.x + item.width; x += 1) {
        const key = `${x},${y}`; expect(covered.has(key)).toBe(false); covered.add(key);
      }
    });
    expect(covered.size).toBe(70);
  });
});

describe('annotationToSvg', () => {
  it('uses normalized coordinates and escapes text', () => {
    const svg = annotationToSvg({ version: 1, sourceAssetId: 'a', shapes: [
      { id: 't', type: 'text', point: { x: .5, y: .25 }, text: '<tag>', color: '#fff', width: .01, fontSize: .1 },
      { id: 'r', type: 'rectangle', start: { x: .8, y: .7 }, end: { x: .2, y: .1 }, color: '#000', width: .01 },
    ] }, 200, 100);
    expect(svg).toContain('x="100"');
    expect(svg).toContain('&lt;tag&gt;');
    expect(svg).toContain('x="40"');
    expect(svg).toContain('width="120');
  });
});
