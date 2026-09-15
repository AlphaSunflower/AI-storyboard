// @vitest-environment jsdom

import React, { useState } from 'react';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RichPromptInput } from './RichPromptInput';
import type { ReferenceImagePoolResult } from '../canvas/referenceImagePool';

const longId = '7c0c826c-3311-4335-b6be-d483b1ca82ce';
const pool: ReferenceImagePoolResult = {
  count: 1,
  entries: [{
    number: 1,
    assetId: 'asset-1',
    sourceNodeId: 'source-1',
    sourceNodeName: longId,
    mediaType: 'image',
    source: 'upstream',
  }],
  assetIds: ['asset-1'],
  getByNumber: () => ({
    number: 1,
    assetId: 'asset-1',
    sourceNodeId: 'source-1',
    sourceNodeName: longId,
    mediaType: 'image',
    source: 'upstream',
  }),
  getByAssetId: () => undefined,
  isValidNumber: (number) => number === 1,
};

describe('RichPromptInput reference tokens', () => {
  afterEach(cleanup);

  it('keeps long source names out of the editable token value', () => {
    const { container } = render(
      <RichPromptInput
        value={`@image#1:${longId}`}
        onChange={() => undefined}
        pool={pool}
        showStrip
      />,
    );

    const chip = container.querySelector('.rt-chip');
    const editable = container.querySelector('.rt-input__editable');
    const hint = container.querySelector('.reference-strip__hint');

    expect(chip?.getAttribute('data-token')).toBe('@image#1');
    expect(chip?.textContent).toContain('#1');
    expect(chip?.textContent).not.toContain('7c0c826c');
    expect(editable?.textContent).not.toContain(longId);
    expect(hint?.textContent).toBe('点击图片加入提示词');
  });

  it('does not reopen the picker after inserting a reference from the strip', async () => {
    function ControlledPrompt() {
      const [value, setValue] = useState('');
      return (
        <RichPromptInput
          value={value}
          onChange={setValue}
          pool={pool}
          showStrip
        />
      );
    }

    const { container } = render(
      <ControlledPrompt />,
    );

    fireEvent.click(container.querySelector('.reference-strip__item')!);

    await waitFor(() => expect(container.querySelector('.reference-picker')).toBeNull());
    expect(container.querySelector('.rt-chip')?.textContent).toContain('#1');
  });
});
