// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ProjectHome } from './ProjectHome';
import type { CanvasSummary, ProjectDocumentV2 } from '../types';

vi.mock('./AssetLibrary', () => ({ AssetLibrary: () => null }));
vi.mock('../hooks/useCanvasCoverUrl', () => ({
  useCanvasCoverUrl: (canvasId: string) => (canvasId === 'canvas-with-cover' ? 'blob:cover-url' : null),
}));

const project: ProjectDocumentV2 = {
  schemaVersion: 2,
  id: 'project-1',
  title: '咕咕嘎嘎',
  canvases: [],
  createdAt: 1,
  updatedAt: 1,
};

const canvas = (id: string, title: string): CanvasSummary => ({
  id,
  projectId: project.id,
  title,
  createdAt: 1,
  updatedAt: Date.now(),
  nodeCount: 2,
  assetCount: 1,
});

const renderHome = (canvases: CanvasSummary[]) => render(
  <ProjectHome
    project={project}
    canvases={canvases}
    onBack={vi.fn()}
    onCreateCanvas={vi.fn()}
    onOpenCanvas={vi.fn()}
    onRenameCanvas={vi.fn()}
    onDeleteCanvas={vi.fn()}
    onRenameProject={vi.fn()}
    onDeleteProject={vi.fn()}
    onExportProject={vi.fn()}
  />,
);

describe('ProjectHome canvas covers', () => {
  it('renders the canvas cover image when one can be resolved', () => {
    const { container } = renderHome([canvas('canvas-with-cover', '画布 17')]);
    const image = container.querySelector<HTMLImageElement>('.project-home__cover-image');
    expect(image).not.toBeNull();
    expect(image?.getAttribute('src')).toBe('blob:cover-url');
  });

  it('keeps the icon placeholder for canvases without images', () => {
    const { container } = renderHome([canvas('canvas-without-cover', '空画布')]);
    expect(container.querySelector('.project-home__cover')).not.toBeNull();
    expect(container.querySelector('.project-home__cover-image')).toBeNull();
    expect(screen.getByText('空画布')).toBeVisible();
  });
});
