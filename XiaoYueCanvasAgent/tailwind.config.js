/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  // 关闭 preflight：保留现有 styles.css 的手写主题，避免全局 reset 破坏已有深墨绿/acid 观感
  corePlugins: { preflight: false },
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // 直接复用 styles.css 已有 token（深墨绿 / acid 荧光绿），零重定义 → 不冲突、不破坏旧变量
        canvas: 'var(--canvas)',
        card: 'var(--card)',
        acid: 'var(--acid)',
        ink: 'var(--ink)',
        clay: 'var(--clay)',
        paper: 'var(--paper)',
        panel: 'var(--panel)',
        // shadcn 语义化别名（radix 组件用）
        background: 'var(--canvas)',
        foreground: '#eef0df',
        border: 'var(--border-dark)',
        ring: 'var(--acid)',
        input: 'var(--border-light)',
        primary: { DEFAULT: 'var(--acid)', foreground: 'var(--ink)' },
        muted: { DEFAULT: 'rgba(238,240,223,0.08)', foreground: 'rgba(238,240,223,0.82)' },
        accent: { DEFAULT: 'rgba(215,255,84,0.12)', foreground: 'var(--acid)' },
        destructive: { DEFAULT: '#ff6b5e', foreground: '#fff' },
        popover: { DEFAULT: '#1c2a25', foreground: '#eef0df' },
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
