/**
 * 设计 Tokens — 沿用 web 端 AI Storyboard 设计系统
 * Anthropic-inspired warm editorial
 */

export const tokens = {
  colors: {
    primary: '#cc785c',        // coral
    primaryLight: '#d9937d',
    primaryDark: '#b5654a',
    canvas: '#faf9f5',         // cream
    ink: '#141413',            // dark
    surface: '#efe9de',        // card bg
    surfaceHover: '#e8e0d2',
    hairline: '#e0d8ca',
    textSecondary: '#6b6560',
    textCaption: '#9a9490',
    success: '#4a9d6e',
    error: '#c44d4d',
    warning: '#c49a3c',
    white: '#ffffff',
  },
  radius: {
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    full: 999,
  },
  spacing: {
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    xl: 20,
    xxl: 24,
  },
  fontSize: {
    xs: 11,
    sm: 13,
    base: 15,
    md: 17,
    lg: 20,
    xl: 24,
    xxl: 28,
    hero: 34,
  },
  fontWeight: {
    normal: '400' as const,
    medium: '500' as const,
    semibold: '600' as const,
    bold: '700' as const,
  },
} as const;
