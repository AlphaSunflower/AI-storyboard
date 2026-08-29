import { describe, expect, it } from 'vitest';
import { buildReferenceImagePool } from './referenceImagePool';
import type { CanvasEdge, CanvasNode } from '../types';
import { buildReferenceToken, collectReferencedImageNumbers, findReferenceTokens, insertReferenceToken, removeReferenceTokenByNumber, stripReferenceMarkers } from './referenceTokenEditing';

const node = (id: string, kind: CanvasNode['data']['kind'], assetId?: string, x = 0): CanvasNode => ({
  id, type: 'media', position: { x, y: 0 }, width: 250, height: 250,
  data: { kind, label: id, prompt: '', status: 'idle', accent: '#fff', ...(assetId ? { assetId } : {}) },
});
const edge = (source: string, target: string): CanvasEdge => ({ id: `e-${source}-${target}`, source, target, type: 'canvas' });

describe('buildReferenceImagePool', () => {
  it('collects direct upstream assets and numbers them from 1', () => {
    const nodes = [node('img', 'image', 'a1', 100), node('gen', 'imageGen'), node('v', 'video', 'v1', 50)];
    const edges = [edge('img', 'gen'), edge('v', 'gen')];
    const pool = buildReferenceImagePool('gen', nodes, edges);
    expect(pool.count).toBe(2);
    // image 优先于 video，再按 X 坐标
    expect(pool.entries[0]).toMatchObject({ assetId: 'a1', number: 1, mediaType: 'image' });
    expect(pool.entries[1]).toMatchObject({ assetId: 'v1', number: 2, mediaType: 'video' });
    expect(pool.getByNumber(1)?.assetId).toBe('a1');
    expect(pool.isValidNumber(3)).toBe(false);
  });

  it('drops duplicate assetIds (same image linked from two nodes)', () => {
    const nodes = [node('img1', 'image', 'a1', 100), node('img2', 'image', 'a1', 200), node('gen', 'imageGen')];
    const edges = [edge('img1', 'gen'), edge('img2', 'gen')];
    const pool = buildReferenceImagePool('gen', nodes, edges, Infinity);
    expect(pool.count).toBe(1);
    expect(pool.entries[0].assetId).toBe('a1');
  });

  it('maxDepth=1 only sees direct upstreams; Infinity follows transitive closure', () => {
    // script -> mid(image) -> gen
    const nodes = [node('script', 'script'), node('mid', 'image', 'mid1', 300), node('gen', 'imageGen')];
    const edges = [edge('script', 'mid'), edge('mid', 'gen')];
    const direct = buildReferenceImagePool('gen', nodes, edges, 1);
    expect(direct.count).toBe(1); // 只看 mid
    expect(direct.entries[0].assetId).toBe('mid1');
    const closure = buildReferenceImagePool('gen', nodes, edges, Infinity);
    expect(closure.count).toBe(1); // script 无资产，传递闭包仍只有 mid
  });

  it('skips nodes without assetId (text/script/prompt/generators)', () => {
    const nodes = [node('t', 'text'), node('p', 'prompt'), node('gen', 'imageGen'), node('orig', 'image', 'a1')];
    const edges = [edge('t', 'gen'), edge('p', 'gen'), edge('orig', 'gen')];
    const pool = buildReferenceImagePool('gen', nodes, edges);
    expect(pool.count).toBe(1);
    expect(pool.entries[0].assetId).toBe('a1');
  });
});

describe('referenceTokenEditing', () => {
  it('parses new (@image#N:file) and legacy (@图N, @N) formats', () => {
    const text = '用 @image#1:cat.png 做参考，还有 @图2 和 @3 号';
    expect(findReferenceTokens(text).map((t) => t.imageNumber)).toEqual([1, 2, 3]);
    expect(findReferenceTokens(text)[0].filename).toBe('cat.png');
    expect(collectReferencedImageNumbers(text)).toEqual([1, 2, 3]);
  });

  it('builds a token with filename', () => {
    expect(buildReferenceToken(3, 'scene.png')).toBe('@image#3:scene.png');
    expect(buildReferenceToken(3)).toBe('@image#3');
  });

  it('inserts a token at cursor with spacing', () => {
    // 光标在末尾插入：token 前自动补空格（紧跟前文），后补空格
    const r = insertReferenceToken('生成一只猫', 5, '@image#1:cat.png');
    expect(r.text).toBe('生成一只猫 @image#1:cat.png ');
    expect(r.cursor).toBeGreaterThan(5);
  });

  it('removes a token by number and cleans double whitespace', () => {
    const text = '图 @图1 和 @图2 结束';
    const out = removeReferenceTokenByNumber(text, 1);
    // 移除 @图1 ，前后单空格保留（图 和 之间仍有单空格），但双空格被压成单空格
    expect(out).not.toContain('@图1');
    expect(out.replace(/\s{2,}/g, ' ')).toBe(out); // 无双空格
  });

  it('strips markers to AI-readable labels', () => {
    expect(stripReferenceMarkers('参考 @图1 和 @image#2:cat.png 生成')).toBe('参考 [参考图1] 和 [参考图2] 生成');
  });
});
