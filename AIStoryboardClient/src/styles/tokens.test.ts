import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('design tokens', () => {
  it('defines opaque aliases used by agent task surfaces', () => {
    const css = readFileSync(resolve(__dirname, 'tokens.css'), 'utf8');

    expect(css).toContain('--color-surface: #faf9f5;');
    expect(css).toContain('--color-bg: #faf9f5;');
  });

  it('ships a dark theme with an opaque panel surface', () => {
    const css = readFileSync(resolve(__dirname, 'theme.css'), 'utf8');

    expect(css).toContain(":root[data-theme='dark']");
    expect(css).toContain('--color-panel: #1e1c19;');
    expect(css).toContain('--color-canvas: #151411;');
  });
});
