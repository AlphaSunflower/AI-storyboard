import { describe, expect, it } from 'vitest';
import {
  buildMultiAnglePrompt,
  multiAngleLabel,
  multiAngleNodeData,
  multiAngleRequest,
  selectMultiAngleModel,
} from './multiAngle';

describe('selectMultiAngleModel', () => {
  it('prefers Seedream 4.5 and falls back to Seedream 4.0', () => {
    expect(selectMultiAngleModel([
      { value: 'gpt-image-2' },
      { value: 'doubao-seedream-4-0-250828' },
      { value: 'doubao-seedream-4-5-251128' },
    ])).toBe('doubao-seedream-4-5-251128');

    expect(selectMultiAngleModel([
      { value: 'gpt-image-2' },
      { value: 'doubao-seedream-4-0-250828' },
    ])).toBe('doubao-seedream-4-0-250828');
  });
});

describe('multi-angle generation contract', () => {
  it('builds a four-view contact sheet prompt for the grid mode', () => {
    const prompt = buildMultiAnglePrompt('grid');

    expect(prompt).toContain('[参考图1]');
    expect(prompt).toContain('2x2 四宫格');
    expect(prompt).toContain('正面');
    expect(prompt).toContain('左侧45度');
    expect(prompt).toContain('背面');
    expect(prompt.length).toBeLessThanOrEqual(300);
  });

  it('asks the model to choose four angles for separate images', () => {
    const prompt = buildMultiAnglePrompt('multi');

    expect(prompt).toContain('[参考图1]');
    expect(prompt).toContain('4张独立');
    expect(prompt).toContain('由你判断');
    expect(prompt).toContain('不重复');
    expect(prompt.length).toBeLessThanOrEqual(300);
  });

  it('maps each mode to the fixed Seedream request parameters', () => {
    expect(multiAngleRequest('grid', 'doubao-seedream-4-5-251128')).toEqual({
      multiAngleMode: 'grid',
      model: 'doubao-seedream-4-5-251128',
      size: '2K',
      n: 1,
    });
    expect(multiAngleRequest('multi', 'doubao-seedream-4-0-250828')).toEqual({
      multiAngleMode: 'multi',
      model: 'doubao-seedream-4-0-250828',
      size: '2K',
      n: 4,
    });
  });

  it('creates image-to-image node data with the generated prompt and fixed request', () => {
    const request = multiAngleRequest('multi', 'doubao-seedream-4-5-251128');

    expect(multiAngleNodeData('源图', request)).toEqual({
      label: '源图 · 多角度',
      prompt: buildMultiAnglePrompt('multi'),
      status: 'idle',
      multiAngle: request,
      model: 'doubao-seedream-4-5-251128',
      size: '2K',
      n: 4,
    });
  });

  it('labels multi-angle generation records by their selected mode', () => {
    expect(multiAngleLabel('grid')).toBe('多角度宫格');
    expect(multiAngleLabel('multi')).toBe('多角度多图');
    expect(multiAngleLabel(undefined)).toBeNull();
  });
});
