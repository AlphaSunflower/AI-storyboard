// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HomePage } from './HomePage';

describe('HomePage', () => {
  it('shows an honest empty local project center', () => {
    render(<HomePage projects={[]} loading={false} onCreate={vi.fn()} onOpen={vi.fn()} onDelete={vi.fn()} onImport={vi.fn()} />);
    expect(screen.getByText('还没有本地项目')).toBeVisible();
    expect(screen.getByText('AI 执行服务未配置')).toBeVisible();
    expect(screen.queryByText(/精选|订阅|credits|Studio Mock/)).not.toBeInTheDocument();
  });
});
