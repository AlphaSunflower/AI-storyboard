import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { userApi } from '../api/user';
import {
  DEFAULT_ACCOUNT_SETTINGS,
  useAccountSettingsStore,
} from './accountSettingsStore';

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
    useAccountSettingsStore.setState({
      settings: DEFAULT_ACCOUNT_SETTINGS,
      loaded: false,
      loading: false,
    });
  });

  afterEach(() => {
    window.localStorage.clear();
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
