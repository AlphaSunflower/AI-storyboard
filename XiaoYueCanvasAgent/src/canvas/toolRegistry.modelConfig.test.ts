import { describe, expect, it } from 'vitest';
import { getTool } from './toolRegistry';

describe('canvas model config tools', () => {
  it('exposes read and write model configuration tools', () => {
    expect(getTool('canvas.model_config.get')).toMatchObject({
      readOnly: true,
      approval: { level: 'auto' },
    });
    expect(getTool('canvas.model_config.set')).toMatchObject({
      readOnly: false,
      approval: { level: 'auto' },
    });
  });
});
