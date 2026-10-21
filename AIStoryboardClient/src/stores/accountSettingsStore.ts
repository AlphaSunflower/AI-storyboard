import { create } from 'zustand';
import {
  userApi,
  type AccountSettings,
  type BusySendBehavior,
  type WorkStepDisplay,
} from '../api/user';
import type { AgentApprovalMode } from '../api/agent';
import {
  applyThemePreference,
  DEFAULT_THEME_PREFERENCE,
  isThemePreference,
  type ThemePreference,
} from '../lib/theme';

/**
 * 账号级通用设置。
 *
 * - 服务端是唯一真源（换设备一致）；`settings` 只是最近一次快照。
 * - 保存走乐观更新：先改本地让开关立刻响应，失败再回滚并抛错给调用方提示。
 */
export const DEFAULT_ACCOUNT_SETTINGS: AccountSettings = {
  defaultApprovalMode: 'PLAN_APPROVAL',
  busySendBehavior: 'steer',
  workStepDisplay: 'detailed',
  themePreference: DEFAULT_THEME_PREFERENCE,
};

const SETTINGS_CACHE_KEY = 'moon.account.settings.v1';

function readCachedSettings(): AccountSettings | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(SETTINGS_CACHE_KEY);
    if (!raw) return null;
    return normalize(JSON.parse(raw) as Partial<AccountSettings>);
  } catch {
    return null;
  }
}

function cacheSettings(settings: AccountSettings | null): void {
  if (typeof window === 'undefined') return;
  try {
    if (settings) {
      window.localStorage.setItem(SETTINGS_CACHE_KEY, JSON.stringify(settings));
    } else {
      window.localStorage.removeItem(SETTINGS_CACHE_KEY);
    }
  } catch {
    // 隐私模式/配额不足时只影响跨标签页同步，不影响当前页。
  }
}

function normalize(
  raw?: Partial<AccountSettings> | null,
  fallback: AccountSettings = DEFAULT_ACCOUNT_SETTINGS,
): AccountSettings {
  const modes: AgentApprovalMode[] = ['PLAN_APPROVAL', 'REQUEST_APPROVAL', 'FULL_ACCESS'];
  const behaviors: BusySendBehavior[] = ['queue', 'steer'];
  const displays: WorkStepDisplay[] = ['thinking', 'standard', 'detailed'];
  return {
    defaultApprovalMode: raw?.defaultApprovalMode && modes.includes(raw.defaultApprovalMode)
      ? raw.defaultApprovalMode
      : fallback.defaultApprovalMode,
    busySendBehavior: raw?.busySendBehavior && behaviors.includes(raw.busySendBehavior)
      ? raw.busySendBehavior
      : fallback.busySendBehavior,
    workStepDisplay: raw?.workStepDisplay && displays.includes(raw.workStepDisplay)
      ? raw.workStepDisplay
      : fallback.workStepDisplay,
    // 旧后端没有 themePreference 时保留本机已选外观，升级期间不把用户闪回浅色。
    themePreference: isThemePreference(raw?.themePreference)
      ? raw.themePreference
      : fallback.themePreference,
  };
}

interface AccountSettingsState {
  settings: AccountSettings;
  loaded: boolean;
  loading: boolean;
  load: (options?: { force?: boolean }) => Promise<void>;
  save: (patch: Partial<AccountSettings>) => Promise<void>;
  reset: () => void;
}

export const useAccountSettingsStore = create<AccountSettingsState>((set, get) => ({
  // 先用本机缓存顶着：刷新后即使接口还没回来，新会话也能沿用上次的全局权限。
  settings: readCachedSettings() ?? DEFAULT_ACCOUNT_SETTINGS,
  loaded: false,
  loading: false,

  load: async (options) => {
    if (get().loading) return;
    if (get().loaded && !options?.force) return;
    set({ loading: true });
    try {
      const response = await userApi.getSettings();
      const settings = normalize(response.data.data, get().settings);
      set({ settings, loaded: true });
      cacheSettings(settings);
      applyThemePreference(settings.themePreference);
    } catch {
      // 未登录或后端暂不可用时保留本机缓存：聊天仍可用，设置页会显示最近一次已知值。
      set({ loaded: false });
    } finally {
      set({ loading: false });
    }
  },

  save: async (patch) => {
    const previous = get().settings;
    const optimistic = normalize({ ...previous, ...patch }, previous);
    set({ settings: optimistic });
    cacheSettings(optimistic);
    if (patch.themePreference) applyThemePreference(optimistic.themePreference);
    try {
      const response = await userApi.updateSettings(patch);
      const settings = normalize(response.data.data, optimistic);
      set({ settings, loaded: true });
      cacheSettings(settings);
      applyThemePreference(settings.themePreference);
    } catch (error) {
      set({ settings: previous });
      cacheSettings(previous);
      applyThemePreference(previous.themePreference);
      throw error;
    }
  },

  reset: () => {
    cacheSettings(null);
    applyThemePreference(DEFAULT_ACCOUNT_SETTINGS.themePreference);
    set({ settings: DEFAULT_ACCOUNT_SETTINGS, loaded: false });
  },
}));

// 同一浏览器多标签页：一个标签页保存设置后，其他标签页立即沿用。
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key !== SETTINGS_CACHE_KEY) return;
    if (!event.newValue) {
      useAccountSettingsStore.setState({
        settings: DEFAULT_ACCOUNT_SETTINGS,
        loaded: false,
      });
      applyThemePreference(DEFAULT_ACCOUNT_SETTINGS.themePreference);
      return;
    }
    try {
      const settings = normalize(
        JSON.parse(event.newValue) as Partial<AccountSettings>,
        useAccountSettingsStore.getState().settings,
      );
      useAccountSettingsStore.setState({ settings, loaded: true });
      applyThemePreference(settings.themePreference);
    } catch {
      // 忽略损坏的缓存值。
    }
  });
}

/** 聊天展示层直接订阅这一项，避免每个组件都解构整个 settings。 */
export function useWorkStepDisplay(): WorkStepDisplay {
  return useAccountSettingsStore((state) => state.settings.workStepDisplay);
}

export function useBusySendBehavior(): BusySendBehavior {
  return useAccountSettingsStore((state) => state.settings.busySendBehavior);
}

export function useThemePreference(): ThemePreference {
  return useAccountSettingsStore((state) => state.settings.themePreference);
}
