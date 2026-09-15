import { expect, test } from '@playwright/test';

const record = {
  id: 'record-redraw',
  producerNodeId: 'producer',
  actionId: 'generateImage',
  attempt: 1,
  jobId: 'job-redraw',
  status: 'running',
  progress: 0.42,
  queueStatus: 'running',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [{ nodeId: 'source', assetId: 'source-asset', role: 'REFERENCE', order: 0 }],
  prompt: '把猫变成蓝色',
  effectivePrompt: '把猫变成蓝色',
  params: { capabilityId: 'image.redraw', model: 'gpt-image-2', size: '1024x1024' },
  model: 'gpt-image-2',
  startedAt: Date.now() - 5_000,
};

test('keeps active work in the queue and exposes provenance in generation records', async ({ page }) => {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    localStorage.setItem('morrow_job_token', `header.${payload}.signature`);
  });

  await page.route('**/api/canvas/**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const path = url.pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });

    if (path === '/projects' && method === 'GET') return json([{ id: 'project-1', title: '生成记录验收' }]);
    if (path === '/projects/project-1' && method === 'GET') return json({ id: 'project-1', title: '生成记录验收' });
    if (path === '/projects/project-1/canvases' && method === 'GET') {
      return json([{ id: 'canvas-1', projectId: 'project-1', title: '画布 1' }]);
    }
    if (path === '/canvases/canvas-1/graph' && method === 'GET') {
      return json({
        graph: {
          schemaVersion: 2,
          nodes: [
            { id: 'source', type: 'image', position: { x: 80, y: 140 }, width: 230, height: 190, data: { kind: 'image', label: '参考图片', prompt: '', status: 'success', accent: '#ff775f', assetId: 'source-asset' } },
            { id: 'producer', type: 'imageToImage', position: { x: 440, y: 140 }, width: 520, height: 420, data: { kind: 'imageToImage', label: '图生图节点', prompt: '把猫变成蓝色', status: 'running', accent: '#b48cff', isGenerating: true, progressPercent: 42 } },
          ],
          edges: [{ id: 'edge-1', source: 'source', sourceHandle: 'image', target: 'producer', targetHandle: 'source', type: 'canvas' }],
          viewport: { x: 0, y: 0, zoom: 1 },
          generationRecords: [record],
        },
        revision: 1,
      });
    }
    if (method === 'PUT') return json({ graph: JSON.parse(route.request().postData() ?? '{}').graph, revision: 2 });
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  await page.goto('/#project=project-1&canvas=canvas-1');
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('生成记录验收');

  await page.getByRole('button', { name: '任务队列' }).click();
  await expect(page.getByText('图生图节点 · 图片重绘')).toBeVisible();
  await expect(page.getByText('生成中', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '关闭任务队列' }).click();

  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.getByRole('heading', { name: '生成记录' })).toBeVisible();
  await page.getByRole('button', { name: /生成图片/ }).click();
  await expect(page.getByText('生成链路')).toBeVisible();
  await expect(page.getByText('来源素材 1')).toBeVisible();
  await expect(page.getByText('由「图生图节点」提交')).toBeVisible();
  await expect(page.locator('.generation-record-card > strong')).toHaveCSS('color', 'rgb(240, 243, 228)');

  await page.setViewportSize({ width: 390, height: 844 });
  const panelBox = await page.locator('.generation-records-panel').boundingBox();
  expect(panelBox).not.toBeNull();
  expect(panelBox!.x).toBeGreaterThanOrEqual(0);
  expect(panelBox!.y).toBeGreaterThanOrEqual(0);
  expect(panelBox!.x + panelBox!.width).toBeLessThanOrEqual(391);
  expect(panelBox!.y + panelBox!.height).toBeLessThanOrEqual(845);
});
