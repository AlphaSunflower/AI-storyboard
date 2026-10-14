/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  // 关闭 preflight：保留现有 styles.css 的手写主题，避免全局 reset 破坏已调好的灰阶观感
  corePlugins: { preflight: false },
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // 直接复用 styles.css 已有 token（唯一色彩真相：白灰阶），零重定义 → 不冲突、不破坏旧变量
        canvas: 'var(--canvas)',
        card: 'var(--card)',
        acid: 'var(--acid)',
        ink: 'var(--ink)',
        clay: 'var(--clay)',
        paper: 'var(--paper)',
        panel: 'var(--panel)',
        // shadcn 语义化别名（radix 组件用）
        background: 'var(--canvas)',
        foreground: 'var(--text)',
        border: 'var(--border-dark)',
        ring: 'var(--acid)',
        input: 'var(--line)',
        primary: { DEFAULT: 'var(--acid)', foreground: 'var(--ink)' },
        muted: { DEFAULT: 'rgba(244,244,245,0.08)', foreground: 'var(--text-muted)' },
        accent: { DEFAULT: 'rgba(244,244,245,0.12)', foreground: 'var(--text)' },
        // 危险动作不再用红：实心白底 + 深色字，靠明度而不是色相
        destructive: { DEFAULT: 'var(--solid)', foreground: 'var(--solid-ink)' },
        popover: { DEFAULT: 'var(--n-raised)', foreground: 'var(--text)' },
      },
      borderRadius: {
        xl: 'calc(var(--radius) + 4px)',
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
    },
  },
  plugins: [],
};
