import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { userApi } from '../api/user';
import {
  DEFAULT_ACCOUNT_SETTINGS,
  useAccountSettingsStore,
} from './accountSettingsStore';
import {
  applyThemePreference,
  THEME_CACHE_KEY,
} from '../lib/theme';

vi.mock('../api/user', () => ({
  userApi: {
    getSettings: vi.fn(),
    updateSettings: vi.fn(),
  },
}));

const CACHE_KEY = 'moon.account.settings.v1';

describe('accountSettingsStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
    applyThemePreference('light');
    useAccountSettingsStore.setState({
      settings: DEFAULT_ACCOUNT_SETTINGS,
      loaded: false,
      loading: false,
    });
  });

  afterEach(() => {
    window.localStorage.clear();
    applyThemePreference('light');
    vi.restoreAllMocks();
  });

  it('persists a saved global approval mode for the next page load', async () => {
    vi.mocked(userApi.updateSettings).mockResolvedValue({
      data: { data: { ...DEFAULT_ACCOUNT_SETTINGS, defaultApprovalMode: 'FULL_ACCESS' } },
    } as never);

    await useAccountSettingsStore.getState().save({ defaultApprovalMode: 'FULL_ACCESS' });

    expect(useAccountSettingsStore.getState().settings.defaultApprovalMode).toBe('FULL_ACCESS');
    expect(JSON.parse(window.localStorage.getItem(CACHE_KEY) ?? '{}').defaultApprovalMode)
      .toBe('FULL_ACCESS');
  });

  it('applies and caches the selected appearance before the request resolves', async () => {
    vi.mocked(userApi.updateSettings).mockResolvedValue({
      data: { data: { ...DEFAULT_ACCOUNT_SETTINGS, themePreference: 'dark' } },
    } as never);

    await useAccountSettingsStore.getState().save({ themePreference: 'dark' });

    expect(useAccountSettingsStore.getState().settings.themePreference).toBe('dark');
    expect(window.localStorage.getItem(THEME_CACHE_KEY)).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('rolls the appearance back when saving fails', async () => {
    vi.mocked(userApi.updateSettings).mockRejectedValue(new Error('network down'));

    await expect(
      useAccountSettingsStore.getState().save({ themePreference: 'dark' }),
    ).rejects.toThrow('network down');

    expect(useAccountSettingsStore.getState().settings.themePreference).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('keeps the local appearance when an older backend omits themePreference', async () => {
    applyThemePreference('dark');
    useAccountSettingsStore.setState({
      settings: { ...DEFAULT_ACCOUNT_SETTINGS, themePreference: 'dark' },
      loaded: false,
      loading: false,
    });
    vi.mocked(userApi.getSettings).mockResolvedValue({
      data: {
        data: {
          defaultApprovalMode: 'PLAN_APPROVAL',
          busySendBehavior: 'steer',
          workStepDisplay: 'detailed',
        },
      },
    } as never);

    await useAccountSettingsStore.getState().load({ force: true });

    expect(useAccountSettingsStore.getState().settings.themePreference).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('follows settings saved by another tab', () => {
    window.dispatchEvent(new StorageEvent('storage', {
      key: CACHE_KEY,
      newValue: JSON.stringify({
        ...DEFAULT_ACCOUNT_SETTINGS,
        defaultApprovalMode: 'FULL_ACCESS',
        busySendBehavior: 'queue',
      }),
    }));

    expect(useAccountSettingsStore.getState().settings).toMatchObject({
      defaultApprovalMode: 'FULL_ACCESS',
      busySendBehavior: 'queue',
    });
  });

  it('clears cached settings on reset', () => {
    window.localStorage.setItem(CACHE_KEY, JSON.stringify(DEFAULT_ACCOUNT_SETTINGS));

    useAccountSettingsStore.getState().reset();

    expect(window.localStorage.getItem(CACHE_KEY)).toBeNull();
    expect(useAccountSettingsStore.getState().settings).toEqual(DEFAULT_ACCOUNT_SETTINGS);
  });
});
