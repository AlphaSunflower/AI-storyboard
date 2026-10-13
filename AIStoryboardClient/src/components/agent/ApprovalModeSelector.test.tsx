import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApprovalModeSelector } from './ApprovalModeSelector';

describe('ApprovalModeSelector', () => {
  it('renders the menu outside its own subtree so ancestor overflow cannot clip it', () => {
    render(
      <div data-testid="clipper" style={{ overflow: 'hidden' }}>
        <ApprovalModeSelector mode="PLAN_APPROVAL" onChange={() => {}} />
      </div>,
    );

    fireEvent.click(screen.getByRole('button', { name: /审批模式/ }));

    const menu = screen.getByRole('menu', { name: '选择审批模式' });
    expect(screen.getByTestId('clipper').contains(menu)).toBe(false);
    expect(document.body.contains(menu)).toBe(true);
  });

  afterEach(() => cleanup());

  it('lists all modes and selects a new policy', () => {
    const onChange = vi.fn();
    render(
      <ApprovalModeSelector
        mode="PLAN_APPROVAL"
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '审批模式：方案批准' }));
    expect(screen.getByRole('menuitemradio', { name: /逐项批准/ })).toBeTruthy();
    expect(screen.getByRole('menuitemradio', { name: /完全访问/ })).toBeTruthy();
    expect(screen.getByRole('menuitemradio', { name: /方案批准/ })).toBeTruthy();

    fireEvent.click(screen.getByRole('menuitemradio', { name: /逐项批准/ }));

    expect(onChange).toHaveBeenCalledWith('REQUEST_APPROVAL');
  });

  it('cannot open while a turn is running', () => {
    render(
      <ApprovalModeSelector
        mode="PLAN_APPROVAL"
        disabled
        onChange={() => undefined}
      />,
    );

    const trigger = screen.getByRole('button', { name: '审批模式：方案批准' });
    expect((trigger as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(trigger);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('requires the custom confirmation before enabling full access', () => {
    const onChange = vi.fn();
    render(
      <ApprovalModeSelector
        mode="PLAN_APPROVAL"
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '审批模式：方案批准' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /完全访问/ }));

    expect(screen.getByRole('alertdialog', { name: '开启完全访问？' })).toBeTruthy();
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '审批模式：方案批准' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /完全访问/ }));
    fireEvent.click(screen.getByRole('button', { name: '确认开启' }));

    expect(onChange).toHaveBeenCalledWith('FULL_ACCESS', { confirmedFullAccess: true });
  });
});
