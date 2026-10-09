import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CONTEXT_WINDOW_TOKENS,
  contextUsageRatio,
  contextWindowTokensFor,
  formatTokenCount,
} from './contextWindow';

describe('contextWindowTokensFor', () => {
  it('falls back to the default window when the model is unknown or missing', () => {
    expect(contextWindowTokensFor()).toBe(DEFAULT_CONTEXT_WINDOW_TOKENS);
    expect(contextWindowTokensFor(null)).toBe(DEFAULT_CONTEXT_WINDOW_TOKENS);
    expect(contextWindowTokensFor('some-unknown-model')).toBe(DEFAULT_CONTEXT_WINDOW_TOKENS);
  });

  it('matches known model families case-insensitively, including dated suffixes', () => {
    expect(contextWindowTokensFor('gpt-4o')).toBe(128_000);
    expect(contextWindowTokensFor('GPT-4o-2024-11-20')).toBe(128_000);
    expect(contextWindowTokensFor('claude-sonnet-4-5')).toBe(200_000);
    expect(contextWindowTokensFor('gemini-2.5-flash')).toBe(1_000_000);
    expect(contextWindowTokensFor('deepseek-flash')).toBe(1_000_000);
    expect(contextWindowTokensFor('deepseek-v3')).toBe(1_000_000);
    expect(contextWindowTokensFor('mimo-v2.6-pro')).toBe(1_000_000);
  });

  it('prefers the longest matching prefix', () => {
    // 带日期/变体后缀时仍按最长命中键取窗口，而不是退到更短的家族键
    expect(contextWindowTokensFor('gpt-4.1-mini')).toBe(1_000_000);
    expect(contextWindowTokensFor('claude-3-5-sonnet-20241022')).toBe(200_000);
  });
});

describe('contextUsageRatio', () => {
  it('clamps to 0..1 and guards against invalid limits', () => {
    expect(contextUsageRatio(0, 100)).toBe(0);
    expect(contextUsageRatio(25, 100)).toBe(0.25);
    expect(contextUsageRatio(400, 100)).toBe(1);
    expect(contextUsageRatio(25, 0)).toBe(0);
    expect(contextUsageRatio(Number.NaN, 100)).toBe(0);
  });
});

describe('formatTokenCount', () => {
  it('renders whole-thousand compact counts', () => {
    expect(formatTokenCount(0)).toBe('0');
    expect(formatTokenCount(640)).toBe('640');
    expect(formatTokenCount(70_000)).toBe('70k');
    expect(formatTokenCount(128_000)).toBe('128k');
    expect(formatTokenCount(1_000_000)).toBe('1M');
  });
});
