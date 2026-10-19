import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import MoonLogo from './MoonLogo';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'matchMedia');
});

function mockReducedMotion(reduced: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: reduced,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

describe('MoonLogo', () => {
  it('keeps the decorative mark out of the accessibility tree', () => {
    const { container } = render(<MoonLogo size={26} />);

    expect(container.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('renders the orbit + sparkle animation only when asked', () => {
    const { container, rerender } = render(<MoonLogo size={26} animated />);

    expect(container.querySelectorAll('animateTransform')).toHaveLength(2);

    rerender(<MoonLogo size={26} />);

    expect(container.querySelectorAll('animateTransform')).toHaveLength(0);
    expect(container.querySelectorAll('animate')).toHaveLength(0);
  });

  it('renders a full starfield at large sizes and a compact one at small sizes', () => {
    const small = render(<MoonLogo size={26} />);
    expect(small.container.querySelectorAll('[data-moon-star]')).toHaveLength(8);
    expect(small.container.querySelectorAll('animate')).toHaveLength(0);
    small.unmount();

    const large = render(<MoonLogo size={64} animated />);
    expect(large.container.querySelectorAll('[data-moon-star]').length).toBeGreaterThanOrEqual(16);
    expect(large.container.querySelectorAll('animate').length).toBeGreaterThanOrEqual(16);
  });

  it('freezes the icon when the user prefers reduced motion', () => {
    mockReducedMotion(true);

    const { container } = render(<MoonLogo size={26} animated />);

    expect(container.querySelectorAll('animateTransform')).toHaveLength(0);
    expect(container.querySelectorAll('animate')).toHaveLength(0);
  });

  it('keeps the two-tone wordmark readable as one label', () => {
    const { container } = render(<MoonLogo size={26} showText />);

    expect(container.textContent).toBe('Moon 智能体');
  });
});
