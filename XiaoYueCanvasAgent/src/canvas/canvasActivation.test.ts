// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest';
import { canvasCommandService } from './canvasCommandService';
import { createProject } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';

describe('canvas activation', () => {
  beforeEach(() => {
    useCanvasStore.setState({
      projectId: null,
      canvasId: null,
      nodes: [],
      edges: [],
      past: [],
      future: [],
      revision: 0,
    });
  });

  it('waits until the activated canvas bridge is ready', async () => {
    const project = await createProject('画布激活测试');
    useCanvasStore.setState({ projectId: project.id, canvasId: 'old-canvas' });

    const activated = new Promise<{ projectId: string; canvasId: string }>((resolve) => {
      window.addEventListener('morrow:canvas-created', (event) => {
        const detail = (event as CustomEvent<{ projectId: string; canvasId: string; activate?: boolean }>).detail;
        if (!detail.activate) return;
        setTimeout(() => {
          window.dispatchEvent(new CustomEvent('morrow:canvas-bridge-ready', { detail }));
          resolve(detail);
        }, 20);
      }, { once: true });
    });

    const result = await canvasCommandService.createCanvas(
      { projectId: project.id, source: 'mcp' },
      { title: '四宫格转场视频（低成本）', activate: true },
    );
    const detail = await activated;

    expect(result.ok).toBe(true);
    expect(result.result).toMatchObject({
      canvasId: detail.canvasId,
      title: '四宫格转场视频（低成本）',
      activated: true,
    });
  });
});
