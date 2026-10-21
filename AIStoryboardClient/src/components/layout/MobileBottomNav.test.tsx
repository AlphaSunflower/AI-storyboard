import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { MobileBottomNav } from './MobileBottomNav';

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}</output>;
}

function renderNav(initialPath: string, props: ComponentProps<typeof MobileBottomNav> = {}) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <MobileBottomNav {...props} />
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('MobileBottomNav', () => {
  afterEach(() => cleanup());

  it('只暴露智能体对话、项目库、我的三个 Tab', () => {
    renderNav('/chat');

    const nav = screen.getByRole('navigation', { name: '手机端主导航' });
    const labels = Array.from(nav.querySelectorAll('button')).map((button) => (
      button.getAttribute('aria-label')
    ));

    expect(labels).toEqual(['智能体对话', '项目库', '我的']);
    expect(screen.queryByLabelText('分镜')).toBeNull();
  });

  it('按路由高亮当前 Tab', () => {
    renderNav('/library?projectId=p1');

    expect(screen.getByLabelText('项目库').getAttribute('aria-current')).toBe('page');
    expect(screen.getByLabelText('智能体对话').getAttribute('aria-current')).toBeNull();
    expect(screen.getByLabelText('我的').getAttribute('aria-current')).toBeNull();
  });

  it('分镜库归属项目库，不被对话 Tab 抢高亮', () => {
    renderNav('/chat/storyboards');

    expect(screen.getByLabelText('项目库').getAttribute('aria-current')).toBe('page');
    expect(screen.getByLabelText('智能体对话').getAttribute('aria-current')).toBeNull();
  });

  it('点击 Tab 跳转到对应页面', () => {
    renderNav('/chat');

    fireEvent.click(screen.getByLabelText('我的'));
    expect(screen.getByTestId('location').textContent).toBe('/account');

    fireEvent.click(screen.getByLabelText('项目库'));
    expect(screen.getByTestId('location').textContent).toBe('/library');
  });

  it('有未读数时显示角标', () => {
    renderNav('/chat', { badges: { chat: 3, mine: 0 } });

    const nav = screen.getByRole('navigation', { name: '手机端主导航' });
    expect(nav.textContent).toContain('3');
    expect(nav.textContent).not.toContain('99+');
  });
});
