import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  applyThemePreference,
  readStoredThemePreference,
  resolveTheme,
  THEME_CACHE_KEY,
} from './theme';

function mockMatchMedia(matches: boolean) {
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  applyThemePreference('light');
});

describe('theme preference', () => {
  it('defaults to light when no stored preference exists', () => {
    expect(readStoredThemePreference()).toBe('light');
  });

  it('applies dark and writes the local first-paint cache', () => {
    applyThemePreference('dark');

    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.dataset.themePreference).toBe('dark');
    expect(window.localStorage.getItem(THEME_CACHE_KEY)).toBe('dark');
  });

  it('resolves system to the current OS appearance', () => {
    mockMatchMedia(true);
    expect(resolveTheme('system')).toBe('dark');

    mockMatchMedia(false);
    expect(resolveTheme('system')).toBe('light');
  });

  it('keeps an explicit light choice even when the system is dark', () => {
    mockMatchMedia(true);
    applyThemePreference('light');

    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
