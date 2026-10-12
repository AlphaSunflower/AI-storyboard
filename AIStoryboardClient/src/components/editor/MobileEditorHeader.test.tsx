import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useProjectStore } from '../../stores/projectStore';
import { MobileEditorHeader } from './MobileEditorHeader';

describe('MobileEditorHeader', () => {
  beforeEach(() => {
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: '测试项目' } as never,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('only renders the script entry when requested', () => {
    const { rerender } = render(
      <MobileEditorHeader onMenuClick={vi.fn()} onScript={vi.fn()} showMenu={false} showScript={false} />,
    );

    expect(screen.queryByLabelText('剧本输入')).toBeNull();

    rerender(
      <MobileEditorHeader onMenuClick={vi.fn()} onScript={vi.fn()} showMenu={false} showScript />,
    );

    expect(screen.getByLabelText('剧本输入')).toBeTruthy();
  });
});
