import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
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

  it('loads slimmed reasoning details once and renders them', async () => {
    const loadDetails = vi.fn().mockResolvedValue({ reasoningDetails: '按需补拉的推理全文' });
    const { container } = render(
      <ReasoningPanel reasoning={{ source: 'agentscope', detailsAvailable: true }} loadDetails={loadDetails} />,
    );

    fireEvent.click(container.querySelector('button') as HTMLButtonElement);

    await waitFor(() => expect(container.textContent).toContain('按需补拉的推理全文'));
    expect(loadDetails).toHaveBeenCalledTimes(1);
    // 关键回归：请求结束后不能继续停在「加载中」，也不能反复重拉。
    expect(container.textContent).not.toContain('思考内容加载中');
    expect(container.textContent).not.toContain('思考详情加载失败');
  });

  it('shows one failure state instead of retrying forever, then retries on demand', async () => {
    const loadDetails = vi.fn().mockRejectedValue(new Error('boom'));
    const { container } = render(
      <ReasoningPanel reasoning={{ source: 'agentscope', detailsAvailable: true }} loadDetails={loadDetails} />,
    );

    fireEvent.click(container.querySelector('button') as HTMLButtonElement);

    await waitFor(() => expect(container.textContent).toContain('思考详情加载失败'));
    expect(loadDetails).toHaveBeenCalledTimes(1);
    expect(container.textContent).not.toContain('思考内容加载中');

    loadDetails.mockResolvedValueOnce({ reasoningDetails: '重试后的推理全文' });
    fireEvent.click(screen.getByText('重试'));

    await waitFor(() => expect(container.textContent).toContain('重试后的推理全文'));
    expect(loadDetails).toHaveBeenCalledTimes(2);
  });
});
