/**
 * 外观主题的唯一运行时。
 *
 * 偏好有三种：light / dark / system。system 不写死结果，而是每次按
 * matchMedia('(prefers-color-scheme: dark)') 解析，并监听系统外观变化。
 * 账号设置同步到服务端；本机 localStorage 只用于首屏在接口返回前避免闪白。
 */

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_CACHE_KEY = 'moon.theme.v1';
export const DEFAULT_THEME_PREFERENCE: ThemePreference = 'light';

const DARK_QUERY = '(prefers-color-scheme: dark)';
const THEME_COLORS: Record<ResolvedTheme, string> = {
  light: '#faf9f5',
  dark: '#171614',
};

let currentPreference: ThemePreference = DEFAULT_THEME_PREFERENCE;
let systemQuery: MediaQueryList | null = null;
let listening = false;

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system';
}

export function readStoredThemePreference(): ThemePreference {
  if (typeof window === 'undefined') return DEFAULT_THEME_PREFERENCE;
  try {
    const stored = window.localStorage.getItem(THEME_CACHE_KEY);
    return isThemePreference(stored) ? stored : DEFAULT_THEME_PREFERENCE;
  } catch {
    return DEFAULT_THEME_PREFERENCE;
  }
}

export function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(DARK_QUERY).matches;
}

export function resolveTheme(preference: ThemePreference): ResolvedTheme {
  if (preference === 'system') return systemPrefersDark() ? 'dark' : 'light';
  return preference;
}

function writeThemeColor(theme: ResolvedTheme): void {
  if (typeof document === 'undefined') return;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', THEME_COLORS[theme]);
}

function paint(preference: ThemePreference): ResolvedTheme {
  currentPreference = preference;
  const theme = resolveTheme(preference);
  if (typeof document !== 'undefined') {
    const root = document.documentElement;
    root.dataset.theme = theme;
    root.dataset.themePreference = preference;
    root.style.colorScheme = theme;
    writeThemeColor(theme);
  }
  return theme;
}

/** 系统外观变化只在「跟随系统」时生效；用户手选的明/暗不会被系统覆盖。 */
function handleSystemThemeChange(): void {
  if (currentPreference === 'system') paint('system');
}

function ensureSystemListener(): void {
  if (listening || typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
  systemQuery = window.matchMedia(DARK_QUERY);
  if (typeof systemQuery.addEventListener === 'function') {
    systemQuery.addEventListener('change', handleSystemThemeChange);
  } else {
    // Safari < 14 的兼容路径；新浏览器不会走这里。
    systemQuery.addListener(handleSystemThemeChange);
  }
  listening = true;
}

/** 启动时调用一次：应用本机偏好，并开始监听系统外观。 */
export function initTheme(): ThemePreference {
  const preference = readStoredThemePreference();
  paint(preference);
  ensureSystemListener();
  return preference;
}

/**
 * 用户主动选择主题：立即生效并写入本机缓存。
 * 服务端持久化由 accountSettingsStore 负责，二者失败时都会回滚。
 */
export function applyThemePreference(preference: ThemePreference): ResolvedTheme {
  const next = isThemePreference(preference) ? preference : DEFAULT_THEME_PREFERENCE;
  paint(next);
  ensureSystemListener();
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(THEME_CACHE_KEY, next);
    } catch {
      // 隐私模式/配额不足时主题仍对当前页生效。
    }
  }
  return resolveTheme(next);
}

export function currentThemePreference(): ThemePreference {
  return currentPreference;
}
