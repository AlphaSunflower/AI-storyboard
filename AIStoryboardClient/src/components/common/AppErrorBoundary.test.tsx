import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppErrorBoundary } from './AppErrorBoundary';

function Boom(): React.ReactNode {
  throw new Error('boom');
}

describe('AppErrorBoundary', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows a retry card instead of a blank page when a page throws', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <AppErrorBoundary>
        <Boom />
      </AppErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText('页面加载出错了')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重新加载/ })).toBeTruthy();
    expect(consoleError).toHaveBeenCalled();
  });

  it('renders children when nothing throws', () => {
    render(
      <AppErrorBoundary>
        <div>正常页面</div>
      </AppErrorBoundary>,
    );

    expect(screen.getByText('正常页面')).toBeTruthy();
  });
});
