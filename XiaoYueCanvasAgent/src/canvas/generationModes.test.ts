import { describe, expect, it } from 'vitest';
import {
  IMAGE_GENERATION_KIND,
  VIDEO_GENERATION_KIND,
  generationModeInputRule,
  mergedGenerationKind,
  resolveActionId,
  resolveNodeMode,
} from './generationModes';
import { getNodeDefinition, listNodeDefinitions } from './graphContracts';

describe('merged generation kinds', () => {
  it('maps every legacy generator kind onto the two merged kinds', () => {
    expect(mergedGenerationKind('textToImage')).toBe(IMAGE_GENERATION_KIND);
    expect(mergedGenerationKind('imageGen')).toBe(IMAGE_GENERATION_KIND);
    expect(mergedGenerationKind('imageToImage')).toBe(IMAGE_GENERATION_KIND);
    expect(mergedGenerationKind('batchImageRefine')).toBe(IMAGE_GENERATION_KIND);
    expect(mergedGenerationKind('textToVideo')).toBe(VIDEO_GENERATION_KIND);
    expect(mergedGenerationKind('videoGen')).toBe(VIDEO_GENERATION_KIND);
    expect(mergedGenerationKind('imageToVideo')).toBe(VIDEO_GENERATION_KIND);
    expect(mergedGenerationKind('firstLastFrameToVideo')).toBe(VIDEO_GENERATION_KIND);
    expect(mergedGenerationKind('referenceToVideo')).toBe(VIDEO_GENERATION_KIND);
    expect(mergedGenerationKind('image')).toBeUndefined();
  });

  it('resolves legacy kinds and legacy videoMode into the declared mode', () => {
    expect(resolveNodeMode('textToImage', {})).toBe('text2image');
    expect(resolveNodeMode('imageToImage', {})).toBe('image2image');
    expect(resolveNodeMode('batchImageRefine', {})).toBe('batchRefine');
    expect(resolveNodeMode('firstLastFrameToVideo', {})).toBe('firstLastFrame');
    expect(resolveNodeMode('imageToVideo', {})).toBe('fullReference');
    expect(resolveNodeMode('videoGen', { videoMode: 'videoReference' })).toBe('videoReference');
    // data.mode 优先于旧字段
    expect(resolveNodeMode('videoGeneration', { mode: 'firstLastFrame', videoMode: 'text2video' })).toBe('firstLastFrame');
    expect(resolveNodeMode('imageGeneration', { mode: 'batchRefine' })).toBe('batchRefine');
  });

  it('keeps a single video and a single image generator in the node library', () => {
    const kinds = listNodeDefinitions().map((definition) => definition.kind);

    expect(kinds.filter((kind) => kind === VIDEO_GENERATION_KIND)).toHaveLength(1);
    expect(kinds.filter((kind) => kind === IMAGE_GENERATION_KIND)).toHaveLength(1);
    ['textToImage', 'imageToImage', 'batchImageRefine', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo']
      .forEach((legacy) => expect(kinds).not.toContain(legacy));
  });

  it('registers wide ports on the merged nodes and keeps legacy aliases resolving', () => {
    const video = getNodeDefinition(VIDEO_GENERATION_KIND);
    const image = getNodeDefinition(IMAGE_GENERATION_KIND);

    expect(video?.inputs.map((port) => [port.id, port.ordered, port.max])).toEqual([
      ['prompt', false, 1],
      ['reference', true, 9],
      ['video', false, 1],
    ]);
    expect(image?.inputs.map((port) => [port.id, port.ordered, port.max])).toEqual([
      ['prompt', false, 1],
      ['reference', true, 14],
    ]);
    expect(image?.actions.map((action) => action.id)).toEqual(['generate', 'redraw']);
    expect(getNodeDefinition('firstLastFrameToVideo')?.kind).toBe(VIDEO_GENERATION_KIND);
    expect(getNodeDefinition('imageToImage')?.kind).toBe(IMAGE_GENERATION_KIND);
  });
});

describe('mode input rules', () => {
  it('requires exactly two frames for first-last-frame and one video for video reference', () => {
    expect(generationModeInputRule(VIDEO_GENERATION_KIND, { mode: 'firstLastFrame' })).toMatchObject({
      referenceMin: 2,
      referenceMax: 2,
      videoMin: 0,
    });
    expect(generationModeInputRule(VIDEO_GENERATION_KIND, { mode: 'videoReference' })).toMatchObject({
      referenceMin: 0,
      videoMin: 1,
    });
    expect(generationModeInputRule(VIDEO_GENERATION_KIND, { mode: 'text2video' })).toMatchObject({
      referenceMin: 0,
      videoMin: 0,
      videoMax: 0,
    });
  });

  it('requires at least one source image for redraw and batch refine', () => {
    expect(generationModeInputRule(IMAGE_GENERATION_KIND, { mode: 'image2image' })?.referenceMin).toBe(1);
    expect(generationModeInputRule(IMAGE_GENERATION_KIND, { mode: 'batchRefine' })?.referenceMin).toBe(1);
    expect(generationModeInputRule(IMAGE_GENERATION_KIND, { mode: 'text2image' })?.referenceMin).toBe(0);
    expect(generationModeInputRule(IMAGE_GENERATION_KIND, { mode: 'fullReference' })?.referenceMin).toBe(0);
  });
});

describe('action resolution', () => {
  it('picks generate for text/full-reference and redraw for image2image/batchRefine', () => {
    expect(resolveActionId('textToImage', {})).toBe('generate');
    expect(resolveActionId('imageGeneration', { mode: 'fullReference' })).toBe('generate');
    expect(resolveActionId('imageGeneration', { mode: 'image2image' })).toBe('redraw');
    expect(resolveActionId('batchImageRefine', {})).toBe('redraw');
    expect(resolveActionId('videoGeneration', { mode: 'videoReference' })).toBe('generate');
    expect(resolveActionId('image', {})).toBeUndefined();
  });
});
