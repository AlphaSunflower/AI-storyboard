import { describe, expect, it } from 'vitest';
import type { RedrawMaskDocument } from '../types';
import { createRedrawJobRequest, hasDrawableRedrawMask } from './redrawMask';

const mask = (marks: RedrawMaskDocument['marks']): RedrawMaskDocument => ({ version: 1, sourceAssetId: 'source', marks });
const context = { clientRequestId: 'request', conversationId: 'conversation', context: { projectId: 'project', nodeIds: ['node'], selectedNodeId: 'node' } };

describe('redraw mask request', () => {
  it('requires a prompt and a painted mask before submission', () => {
    expect(() => createRedrawJobRequest('source', '', mask([]), { count: 1, modelTier: 'normal', resolution: '2K' }, null, context)).toThrow('请输入重绘要求');
    expect(() => createRedrawJobRequest('source', '改成蓝色', mask([]), { count: 1, modelTier: 'normal', resolution: '2K' }, null, context)).toThrow('请先在图片上绘制蒙版');
    expect(hasDrawableRedrawMask(mask([{ id: 'erase', type: 'brush', operation: 'erase', points: [{ x: .2, y: .2 }], width: .03 }]))).toBe(false);
  });

  it('normalizes the provider-neutral redraw request', () => {
    const document = mask([{ id: 'paint', type: 'rectangle', operation: 'paint', start: { x: .1, y: .2 }, end: { x: .5, y: .7 } }]);
    const request = createRedrawJobRequest('source', '  将椅子改成蓝色  ', document, { count: 3, modelTier: 'normal', resolution: '4K' }, { type: 'asset', assetId: 'reference', label: '参考图' }, context);
    expect(request.capabilityId).toBe('image.redraw');
    expect(request.sourceAssetIds).toEqual(['source', 'reference']);
    expect(request.params).toMatchObject({ prompt: '将椅子改成蓝色', count: 3, modelTier: 'normal', resolution: '1K', aspectRatio: 'source' });
    expect(request.params.mask).toEqual(document);
  });
});
