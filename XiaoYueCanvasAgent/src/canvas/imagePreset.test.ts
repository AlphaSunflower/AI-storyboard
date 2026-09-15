import { describe, expect, it } from 'vitest';
import {
  DEFAULT_IMAGE_PRESET_KEY,
  IMAGE_GEN_TYPES,
  buildImagePresetPrompt,
  validateImagePresetReferences,
} from './imagePreset';

describe('buildImagePresetPrompt', () => {
  it('appends the selected preset instruction to the user prompt', () => {
    expect(buildImagePresetPrompt('portrait', '一只黑猫')).toContain('一只黑猫');
    expect(buildImagePresetPrompt('portrait', '一只黑猫')).toContain('纯白或简洁背景');
  });

  it('keeps the normal prompt unchanged and uses the custom key by default', () => {
    expect(DEFAULT_IMAGE_PRESET_KEY).toBe('custom');
    expect(buildImagePresetPrompt('custom', '一只黑猫')).toBe('一只黑猫');
  });

  it('renames labels and adds the outpainting preset', () => {
    expect(IMAGE_GEN_TYPES.find((item) => item.key === 'portrait')?.label).toBe('参考物四视图');
    expect(IMAGE_GEN_TYPES.find((item) => item.key === 'custom')?.label).toBe('普通');
    expect(IMAGE_GEN_TYPES.find((item) => item.key === 'expand')?.label).toBe('扩图');
  });

  it('requires at least two reference images for a multi-grid generation', () => {
    expect(validateImagePresetReferences('multi-grid', 0)).toBe('多宫格至少需要 2 张参考图');
    expect(validateImagePresetReferences('multi-grid', 1)).toBe('多宫格至少需要 2 张参考图');
    expect(validateImagePresetReferences('multi-grid', 2)).toBeNull();
  });
});
