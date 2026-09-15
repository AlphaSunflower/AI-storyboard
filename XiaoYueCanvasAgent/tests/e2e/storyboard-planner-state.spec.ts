import { expect, test } from '@playwright/test';

test('shows planner progress inline without covering the action button', async ({ page }) => {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    localStorage.setItem('morrow_job_token', `header.${payload}.signature`);
  });
  await page.route('**/api/canvas/**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const path = url.pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/projects/planner-project' && method === 'GET') return json({ id: 'planner-project', title: '分镜状态测试' });
    if (path === '/projects/planner-project/canvases' && method === 'GET') {
      return json([{ id: 'planner-canvas', projectId: 'planner-project', title: '默认画布' }]);
    }
    if (path === '/canvases/planner-canvas/graph' && method === 'GET') {
      return json({
        graph: {
          schemaVersion: 2,
          nodes: [
            {
              id: 'script-1',
              type: 'script',
              position: { x: 40, y: 80 },
              width: 420,
              height: 320,
              data: { kind: 'script', label: '剧本', prompt: '', status: 'idle', accent: '#a9c9f5', scriptText: '雨夜追逐' },
            },
            {
              id: 'planner-1',
              type: 'storyboardPlanner',
              position: { x: 540, y: 80 },
              width: 520,
              height: 420,
              data: {
                kind: 'storyboardPlanner',
                label: '分镜拆解',
                prompt: '雨夜追逐',
                status: 'running',
                accent: '#ff8a3d',
                isGenerating: true,
                progressPercent: 42,
              },
            },
          ],
          edges: [{
            id: 'script-to-planner',
            source: 'script-1',
            sourceHandle: 'script',
            target: 'planner-1',
            targetHandle: 'script',
            type: 'canvas',
          }],
          viewport: { x: 0, y: 0, zoom: 1 },
          generationRecords: [{
            id: 'plan-record',
            producerNodeId: 'planner-1',
            actionId: 'plan',
            attempt: 1,
            jobId: 'job-plan',
            status: 'running',
            progress: 0.42,
            queueStatus: 'running',
            materializationStatus: 'pending',
            materializedAssetIds: [],
            resultNodeIds: [],
            sources: [],
            prompt: '雨夜追逐',
            effectivePrompt: '雨夜追逐',
            params: {},
            startedAt: Date.now() - 3000,
          }],
        },
        revision: 1,
      });
    }
    if (method === 'PUT') return json({ graph: JSON.parse(route.request().postData() ?? '{}').graph, revision: 2 });
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  await page.goto('/#project=planner-project&canvas=planner-canvas');
  const planner = page.locator('.react-flow__node[data-id="planner-1"]');
  await expect(planner.getByText('已接入剧本')).toBeVisible();
  const button = planner.getByRole('button', { name: '生成中 42%' });
  await expect(button).toBeDisabled();
  await expect(planner.getByText('任务已提交，正在生成结构化分镜…')).toBeVisible();
  await expect(planner.locator('.node-upload-error')).toHaveCount(0);

  const buttonBox = await button.boundingBox();
  const feedbackBox = await planner.locator('.storyboard-planner-feedback').boundingBox();
  expect(buttonBox).not.toBeNull();
  expect(feedbackBox).not.toBeNull();
  expect(feedbackBox!.y).toBeGreaterThanOrEqual(buttonBox!.y + buttonBox!.height - 1);
});
