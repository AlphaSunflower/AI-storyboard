// @vitest-environment jsdom

import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MoonChatWindow } from './MoonChatWindow';
import { clearToken, setJobToken } from '../services/jobAuth';
import { useCanvasStore } from '../store/canvasStore';

describe('MoonChatWindow', () => {
  afterEach(() => {
    cleanup();
    clearToken();
  });

  it('loads the embedded chat without exposing the access token in the iframe URL', () => {
    setJobToken('header.payload.signature', 'user-1');
    useCanvasStore.setState({ projectId: 'project-1', canvasId: 'canvas-1' });

    render(<MoonChatWindow />);

    const src = screen.getByTitle('Moon 智能体').getAttribute('src') ?? '';
    expect(src).not.toContain('token=');
    expect(src).toContain('surface=canvas-edit');
    expect(src).toContain('projectId=project-1');
    expect(src).toContain('canvasId=canvas-1');
    // 子站恒定画布模式：画布站点锁定，不提供退出入口
    expect(src).toContain('canvasLock=1');
  });
});
