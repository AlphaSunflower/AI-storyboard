import { afterEach, describe, expect, it, vi } from 'vitest';
import { createId } from './id';

describe('createId', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('creates a UUID when randomUUID is unavailable on a LAN HTTP origin', () => {
    let next = 0;
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        bytes.forEach((_, index) => { bytes[index] = next++ & 0xff; });
        return bytes;
      },
    });

    expect(createId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});
