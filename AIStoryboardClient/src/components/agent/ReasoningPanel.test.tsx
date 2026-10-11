import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ReasoningPanel } from './ReasoningPanel';

describe('ReasoningPanel', () => {
  it('renders live reasoning text once without a duplicate preview', () => {
    const { container } = render(
      <ReasoningPanel
        streaming
        reasoning={{
          summary: 'Simplest: just answer.',
          details: 'The user forbids tools. Simplest: just answer.',
          source: 'agentscope',
        }}
      />,
    );

    expect(container.textContent?.match(/Simplest: just answer\./g)).toHaveLength(1);
    expect(container.querySelector('[aria-live]')).toBeNull();
  });
});
