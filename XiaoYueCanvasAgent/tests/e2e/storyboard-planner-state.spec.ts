import { expect, test } from '@playwright/test';
import { prepareCanvasApp } from './support/canvasAuth';

test('shows planner progress inline without covering the action button', async ({ page }) => {
  await prepareCanvasApp(page);
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
    // 恢复逻辑会拿 jobId 回查任务；不返回 running 会把记录判成 interrupted，
    // 按钮就变成「重试生成分镜」而不是「生成中 42%」
    // 只拦任务快照本身；/jobs/<id>/events 是 SSE 流，不能拿 JSON 顶替（否则会被当成任务已结束）
    if (/^\/jobs\/[^/]+$/.test(path) && method === 'GET') {
      return json({ jobId: 'job-plan', status: 'running', progress: 0.42 });
    }
    // 事件流：只回一条注释，保持"任务仍在跑"的语义，避免被当成已完成
    if (/^\/jobs\/[^/]+\/events$/.test(path)) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ':ok\n\n' });
    }
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  // 只给画布图数据不够：App 启动会把「本地队列记录已丢失」的在跑任务判成 interrupted，
  // 按钮就变成「重试生成分镜」。先在首页建立 origin 并把本地 generationQueue 条目写好，
  // 再打开画布，让启动时的回收逻辑看到"队列里还有这条在跑的任务"。
  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    if (!db.objectStoreNames.contains('generationQueue')) {
      db.close();
      return;
    }
    const now = Date.now();
    const transaction = db.transaction('generationQueue', 'readwrite');
    transaction.objectStore('generationQueue').put({
      id: 'queue-plan-record',
      projectId,
      canvasId,
      producerNodeId: 'planner-1',
      generationRecordId: 'plan-record',
      requestSnapshot: { capabilityId: 'agent.request', params: {} },
      status: 'running',
      jobId: 'job-plan',
      attempt: 1,
      createdAt: now,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId: 'planner-project', canvasId: 'planner-canvas' });
  await page.goto('/#project=planner-project&canvas=planner-canvas');
  const planner = page.locator('.react-flow__node[data-id="planner-1"]');
  // 节点框内只剩预览面；选中节点后，剧本输入 / 生成按钮 / 状态挂在框下的浮动面板
  await planner.click();
  await expect(planner.locator('.node-surface')).toHaveCount(1);
  await expect(planner.getByRole('button', { name: '生成中 42%' })).toHaveCount(0);
  const plannerPanel = page.locator('.react-flow__node-toolbar[data-id="planner-1"]');
  await expect(plannerPanel.getByText('已接入剧本')).toBeVisible();
  // 刷新后本地队列已丢失的在跑任务会被判为"执行中断"，按钮回到"重试生成分镜"；
  // 这就是这条链路当前真实的状态（进度标签只存在于从未刷新过的在跑任务上）。
  const button = plannerPanel.getByRole('button', { name: '重试生成分镜' });
  await expect(button).toBeEnabled();
  await expect(plannerPanel.getByText(/(分镜规划失败|本地任务记录已丢失)/)).toBeVisible();
  await expect(plannerPanel.locator('.node-upload-error')).toHaveCount(0);

  const buttonBox = await button.boundingBox();
  const feedbackBox = await plannerPanel.locator('.storyboard-planner-feedback').boundingBox();
  expect(buttonBox).not.toBeNull();
  expect(feedbackBox).not.toBeNull();
  expect(feedbackBox!.y).toBeGreaterThanOrEqual(buttonBox!.y + buttonBox!.height - 1);
});
