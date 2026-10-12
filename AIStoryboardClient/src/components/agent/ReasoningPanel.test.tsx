import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ReasoningPanel } from './ReasoningPanel';

const reasoning = {
  summary: 'Simplest: just answer.',
  details: 'The user forbids tools. Simplest: just answer.',
  source: 'agentscope',
};

describe('ReasoningPanel', () => {
  it('renders live reasoning text once without a duplicate preview', () => {
    const { container } = render(
      <ReasoningPanel streaming reasoning={reasoning} />,
    );

    expect(container.textContent?.match(/Simplest: just answer\./g)).toHaveLength(1);
    expect(container.querySelector('[aria-live]')).toBeNull();
  });

  it('shows the running lattice loader with the live label while streaming', () => {
    const { container } = render(<ReasoningPanel streaming reasoning={reasoning} />);
    const loader = container.querySelector('.lattice-loader');

    expect(loader?.getAttribute('data-status')).toBe('working');
    expect(container.textContent).toContain('思考中');
    expect(container.querySelectorAll('.lattice-loader__run .lattice-loader__cell')).toHaveLength(9);
    expect(container.querySelectorAll('.lattice-loader__mark .lattice-loader__cell')).toHaveLength(9);
  });

  it('freezes the backend duration into the done mark', () => {
    const { container } = render(
      <ReasoningPanel reasoning={{ ...reasoning, durationMs: 151_000 }} />,
    );
    const loader = container.querySelector('.lattice-loader');

    expect(loader?.getAttribute('data-status')).toBe('done');
    expect(container.querySelector('.lattice-loader__timer')?.textContent).toBe('2m 31.0s');
    expect(container.textContent).toContain('已思考，用时 2 分 31 秒');
  });

  it('omits the stopwatch when the backend sends no duration', () => {
    const { container } = render(<ReasoningPanel reasoning={reasoning} />);

    expect(container.querySelector('.lattice-loader__timer')).toBeNull();
    expect(container.textContent).toContain('已思考');
  });

  it('still toggles the reasoning body on click', () => {
    const { container } = render(<ReasoningPanel reasoning={reasoning} />);

    expect(container.querySelector('.reasoning-expand')).toBeNull();
    fireEvent.click(container.querySelector('button') as HTMLButtonElement);
    expect(container.querySelector('.reasoning-expand')).not.toBeNull();
  });
});
