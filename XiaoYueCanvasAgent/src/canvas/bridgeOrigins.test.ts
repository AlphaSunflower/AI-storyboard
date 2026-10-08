import { describe, expect, it } from 'vitest';
import { isBridgeOriginAllowed, parseBridgeOrigins } from './bridgeOrigins';

describe('bridge origin policy', () => {
  it('allows both the canvas dev server and the main app proxy origin', () => {
    const origins = parseBridgeOrigins();

    expect(isBridgeOriginAllowed('http://localhost:3000', origins)).toBe(true);
    expect(isBridgeOriginAllowed('http://localhost:5173', origins)).toBe(true);
  });

  it('keeps an explicit environment override authoritative', () => {
    const origins = parseBridgeOrigins('http://localhost:13000');

    expect(isBridgeOriginAllowed('http://localhost:13000', origins)).toBe(true);
    expect(isBridgeOriginAllowed('http://localhost:5173', origins)).toBe(false);
  });
});
