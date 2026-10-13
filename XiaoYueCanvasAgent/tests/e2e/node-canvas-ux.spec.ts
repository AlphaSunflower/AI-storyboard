import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });
import { showAllNodes } from './node-display';

test('drags a shot node from its handle and opens details in the inspector', async ({ page }) => {
  const projectId = 'node-ux-project';
  const canvasId = 'node-ux-canvas';
  const submittedJobRequests: Array<Record<string, unknown>> = [];
  // 画布启动依赖 /api/auth/refresh 换 token；这里自闭环，避免依赖本机 ApiGateway。
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '节点交互测试' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === '/models' && request.method() === 'GET') {
      return route.fulfill({
        json: [{
          value: 'seedream-4',
          label: 'Seedream 4',
          default: 'true',
          params: JSON.stringify({
            sizes: ['1K', '2K', '4K'],
            sizeDefault: '2K',
            qualities: ['standard', 'high'],
            qualityDefault: 'high',
            styles: ['anime', 'realistic'],
            styleDefault: 'anime',
            aspectRatios: ['1:1', '16:9'],
            aspectRatioDefault: '16:9',
            n: { min: 1, max: 4, default: 2 },
            refImages: { min: 1, max: 4 },
            maxPromptChars: 300,
            spec: {
              request: [
                { key: 'size', label: 'size', type: 'string' },
                { key: 'quality', label: 'quality', type: 'string' },
                { key: 'style', label: 'style', type: 'string' },
                { key: 'aspect_ratio', label: 'aspect_ratio', type: 'string' },
                { key: 'n', label: 'n', type: 'range', min: 1, max: 4, default: 2 },
                { key: 'response_format', label: 'response_format', type: 'string', default: 'url' },
                { key: 'prompt_optimizer', label: 'prompt_optimizer', type: 'bool', default: true },
              ],
            },
          }),
        }],
      });
    }
    if (path === '/capabilities' && request.method() === 'GET') {
      return route.fulfill({
        json: [
          { id: 'image.generate', label: '生图', available: true },
          { id: 'image.redraw', label: '图改图', available: true },
        ],
      });
    }
    if (path === '/jobs' && request.method() === 'POST') {
      submittedJobRequests.push(request.postDataJSON() as Record<string, unknown>);
      return route.fulfill({ json: { jobId: `job-${Date.now()}` } });
    }
    if (/^\/jobs\/[^/]+\/events$/.test(path)) {
      return route.fulfill({
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
        body: 'event: job.completed\ndata: {"type":"job.completed"}\n\n',
      });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, generationRecords: [] };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    // 提交生成任务前会拉取画布资产清单；没有后端时返回空清单
    if (path === `/canvases/${canvasId}/assets`) {
      return route.fulfill({ json: [] });
    }
    if (path === '/assets/upload' && request.method() === 'POST') {
      return route.fulfill({
        json: {
          id: 'uploaded-asset',
          canvasId,
          type: 'image',
          mimeType: 'image/png',
          url: '/api/files/images/uploaded.png',
        },
      });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const now = Date.now();
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
      if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
      if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
      if (!db.objectStoreNames.contains('generationQueue')) db.createObjectStore('generationQueue', { keyPath: 'id' });
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const imageBlob = await new Promise<Blob>((resolve) => {
      const canvas = document.createElement('canvas');
      canvas.width = 24;
      canvas.height = 24;
      const context = canvas.getContext('2d')!;
      context.fillStyle = '#ff775f';
      context.fillRect(0, 0, 24, 24);
      canvas.toBlob((blob) => resolve(blob!), 'image/png');
    });
    const transaction = db.transaction(['legacyProjects', 'canvases', 'assetMeta', 'assetVariants'], 'readwrite');
    transaction.objectStore('legacyProjects').put({
      schemaVersion: 2,
      id: projectId,
      title: '节点交互测试',
      canvases: [canvasId],
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1,
      id: canvasId,
      projectId,
      title: '默认画布',
      nodes: [
        {
          id: 'shot-ux-1',
          type: 'shot',
          position: { x: 360, y: 220 },
          width: 360,
          height: 300,
          data: {
            kind: 'shot',
            label: '镜头 01',
            prompt: '雨夜街头，角色撑伞走入镜头，霓虹灯在积水中反射',
            status: 'idle',
            accent: '#ffb3a1',
            shotId: 'shot-ux-1',
            shotKey: 'shot-ux-1',
            shotOrdinal: 1,
            sceneDescription: '雨夜街头，角色撑伞走入镜头，霓虹灯在积水中反射',
            shotType: '全景',
            cameraMovement: '缓慢推近',
            durationSeconds: 5,
            imagePrompt: '雨夜街道，霓虹灯，角色撑黑伞，电影感构图',
            videoPrompt: '雨滴落下，角色缓慢走入画面，镜头平稳推近',
            negativePrompt: '',
          },
        },
        {
          id: 'video-ux-1',
          type: 'video',
          position: { x: 840, y: 220 },
          width: 420,
          height: 236,
          data: {
            kind: 'video',
            label: '视频占位',
            prompt: '',
            status: 'idle',
            accent: '#8f9cff',
          },
        },
        {
          id: 'script-ux-1',
          type: 'script',
          position: { x: 360, y: 600 },
          width: 420,
          height: 320,
          data: {
            kind: 'script',
            label: '剧本',
            prompt: '',
            status: 'idle',
            accent: '#a9c9f5',
            scriptText: '',
          },
        },
        {
          id: 'image-ux-1',
          type: 'image',
          position: { x: 840, y: 600 },
          width: 240,
          height: 240,
          data: {
            kind: 'image',
            label: '图片源',
            prompt: '',
            status: 'success',
            accent: '#ff775f',
            assetId: 'image-ux-asset',
            mediaLoadState: 'ready',
          },
        },
        {
          id: 'batch-ux-1',
          type: 'imageGeneration',
          position: { x: 1220, y: 600 },
          width: 480,
          height: 560,
          data: {
            kind: 'imageGeneration',
            mode: 'batchRefine',
            label: '图片生成',
            prompt: '变成卡通形象',
            status: 'idle',
            accent: '#b48cff',
          },
        },
        {
          id: 'image-to-image-ux-1',
          type: 'imageGeneration',
          position: { x: 1220, y: 1100 },
          width: 480,
          height: 560,
          data: {
            kind: 'imageGeneration',
            mode: 'image2image',
            label: '图片生成',
            prompt: '把图片转换为动画风格',
            status: 'idle',
            accent: '#b48cff',
          },
        },
      ],
      edges: [
        {
          id: 'edge-image-batch',
          source: 'image-ux-1',
          sourceHandle: 'image',
          target: 'batch-ux-1',
          targetHandle: 'reference',
          type: 'canvas',
        },
        {
          id: 'edge-image-to-image',
          source: 'image-ux-1',
          sourceHandle: 'image',
          target: 'image-to-image-ux-1',
          targetHandle: 'reference',
          type: 'canvas',
        },
      ],
      viewport: { x: 40, y: 40, zoom: 0.8 },
      generationRecords: [],
      revision: 1,
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('assetMeta').put({
      id: 'image-ux-asset',
      kind: 'image',
      name: 'image-ux.png',
      mimeType: 'image/png',
      bytes: imageBlob.size,
      width: 24,
      height: 24,
      projectId,
      canvasId,
      libraryState: 'project',
      createdAt: now,
    });
    for (const variant of ['original', 'thumbnail', 'preview']) {
      transaction.objectStore('assetVariants').put({ assetId: 'image-ux-asset', variant, blob: imageBlob });
    }
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await showAllNodes(page);

  const node = page.locator('.react-flow__node[data-id="shot-ux-1"]');
  await expect(node).toBeVisible();
  const handle = node.locator('.ai-node-external-title.node-drag-handle');
  await expect(handle).toBeVisible();
  await expect(node.locator('.shot-node-view input, .shot-node-view textarea')).toHaveCount(0);

  const before = await node.boundingBox();
  const handleBox = await handle.boundingBox();
  expect(before).not.toBeNull();
  expect(handleBox).not.toBeNull();

  await page.mouse.move(handleBox!.x + handleBox!.width / 2, handleBox!.y + handleBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(handleBox!.x + handleBox!.width / 2 + 140, handleBox!.y + handleBox!.height / 2 + 90, { steps: 8 });
  await page.mouse.up();

  const after = await node.boundingBox();
  expect(after).not.toBeNull();
  expect(Math.abs(after!.x - before!.x) + Math.abs(after!.y - before!.y)).toBeGreaterThan(100);

  await page.getByRole('button', { name: 'Fit View' }).click();
  const videoNode = page.locator('.react-flow__node[data-id="video-ux-1"]');
  const videoHandle = videoNode.locator('.ai-node-external-title.node-drag-handle');
  await expect(videoHandle).toBeVisible();
  const videoBefore = await videoNode.boundingBox();
  const videoHandleBox = await videoHandle.boundingBox();
  expect(videoBefore).not.toBeNull();
  expect(videoHandleBox).not.toBeNull();
  await page.mouse.move(videoHandleBox!.x + videoHandleBox!.width / 2, videoHandleBox!.y + videoHandleBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(videoHandleBox!.x + videoHandleBox!.width / 2 - 110, videoHandleBox!.y + videoHandleBox!.height / 2 + 70, { steps: 8 });
  await page.mouse.up();
  const videoAfter = await videoNode.boundingBox();
  expect(videoAfter).not.toBeNull();
  expect(Math.abs(videoAfter!.x - videoBefore!.x) + Math.abs(videoAfter!.y - videoBefore!.y)).toBeGreaterThan(80);

  // 拖动后视频节点仍处于选中态，它框下的编辑面板会浮在下方节点之上；先点空白处取消选中再操作剧本节点
  const paneBox = await page.locator('.react-flow__pane').boundingBox();
  await page.mouse.click(paneBox!.x + paneBox!.width - 160, paneBox!.y + 90);
  await expect(page.locator('.react-flow__node-toolbar[data-id="video-ux-1"]')).toHaveCount(0);

  const scriptNode = page.locator('.react-flow__node[data-id="script-ux-1"]');
  await expect(scriptNode.getByRole('button', { name: /创建分镜拆解|生成分镜/ })).toHaveCount(0);
  const scriptEditor = page.getByLabel('剧本内容');
  await scriptEditor.click();
  await scriptEditor.pressSequentially('wwww', { delay: 5 });
  await expect(scriptEditor).toHaveValue('wwww');
  await page.waitForTimeout(320);
  await expect(scriptEditor).toHaveValue('wwww');

  const imageToImageNode = page.locator('.react-flow__node[data-id="image-to-image-ux-1"]');
  await imageToImageNode.scrollIntoViewIfNeeded();
  await imageToImageNode.click();
  const redrawComposer = page.locator('.react-flow__node-toolbar[data-id="image-to-image-ux-1"]');
  await expect(redrawComposer).toBeVisible();
  await expect(redrawComposer.getByRole('button', { name: '图生图' })).toHaveAttribute('aria-pressed', 'true');
  await expect(redrawComposer.getByLabel('模型')).toBeVisible();
  await expect(redrawComposer.getByRole('button', { name: '执行重绘' })).toBeVisible();

  const batchNode = page.locator('.react-flow__node[data-id="batch-ux-1"]');
  await batchNode.scrollIntoViewIfNeeded();
  await expect(batchNode).toBeVisible();
  // composer 挂在节点下方（react-flow__node-toolbar），选中节点后才出现
  await batchNode.click();
  const batchComposer = page.locator('.react-flow__node-toolbar[data-id="batch-ux-1"]');
  await expect(batchComposer).toBeVisible();
  const batchPrompt = await batchComposer.locator('.batch-refine-content > textarea').boundingBox();
  const batchItems = await batchComposer.locator('.batch-refine-items').boundingBox();
  const batchSubmit = await batchComposer.locator('.gen-send').boundingBox();
  const batchView = await batchComposer.locator('.gen-composer').boundingBox();
  expect(batchPrompt).not.toBeNull();
  expect(batchItems).not.toBeNull();
  expect(batchSubmit).not.toBeNull();
  expect(batchView).not.toBeNull();
  expect(batchPrompt!.y + batchPrompt!.height).toBeLessThanOrEqual(batchItems!.y + 1);
  expect(batchItems!.y + batchItems!.height).toBeLessThanOrEqual(batchSubmit!.y + 1);
  expect(batchItems!.y + batchItems!.height).toBeLessThanOrEqual(batchView!.y + batchView!.height + 1);
  expect(batchSubmit!.y + batchSubmit!.height).toBeLessThanOrEqual(batchView!.y + batchView!.height + 1);
  await expect(batchComposer.getByLabel('模型')).toBeVisible();
  await expect(batchComposer.getByRole('group', { name: '生成模式' })).toBeVisible();
  await batchComposer.getByRole('button', { name: '参数设置', exact: true }).click();
  await expect(batchComposer.getByRole('group', { name: '分辨率' })).toBeVisible();
  await expect(batchComposer.getByRole('group', { name: '宽高比' })).toBeVisible();
  await expect(batchComposer.getByRole('textbox', { name: '生成个数' })).toBeVisible();
  // 参数浮层不出现「输出格式」「码率档位」
  await expect(batchComposer.getByLabel('输出格式')).toHaveCount(0);
  await expect(batchComposer.getByLabel('码率档位')).toHaveCount(0);
  await batchComposer.getByRole('button', { name: '参数设置', exact: true }).click();
  await batchComposer.getByRole('button', { name: '批量完善图片' }).dispatchEvent('click');
  await expect(batchComposer.locator('.batch-refine-content > .node-upload-error')).toHaveText('已提交 1 个改图任务');
  await expect(batchComposer.getByRole('button', { name: '批量完善图片' })).toBeEnabled();
  await expect(batchComposer.locator('.batch-refine-status')).toContainText(/已提交|生成中|已完成|失败/);
  expect(submittedJobRequests).toHaveLength(1);
  const batchParams = submittedJobRequests[0].params as Record<string, unknown>;
  expect(batchParams.actionId).toBe('redraw');
  expect(batchParams.referenceAssetIds).toContain('uploaded-asset');

  const imageNode = page.locator('.react-flow__node[data-id="image-ux-1"]');
  await imageNode.click();
  await expect(page.getByTitle('生图 / 再次生成')).toHaveCount(0);
  await expect(page.locator('.image-generate-panel')).toHaveCount(0);
  await expect(imageNode.getByText('生图', { exact: true })).toHaveCount(0);

  // 镜头节点的操作挂在框下的浮动面板（节点框内只剩预览面，选中后出现）
  await node.locator('.ai-node-external-title').click();
  await page.locator('.react-flow__node-toolbar[data-id="shot-ux-1"]').getByRole('button', { name: '编辑详情' }).click();
  await expect(page.getByText('节点属性', { exact: true })).toBeVisible();
  await expect(page.getByLabel('画面描述')).toBeVisible();

  await page.mouse.click(700, 450);
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const nodeCount = await page.locator('.react-flow__node').count();
  await page.keyboard.press('Control+KeyA');
  await expect(page.locator('.react-flow__node.selected')).toHaveCount(nodeCount);
  await page.keyboard.press('Control+c');
  await page.keyboard.press('Control+v');
  await expect(page.locator('.react-flow__node')).toHaveCount(nodeCount * 2);
  await page.keyboard.press('Control+d');
  await expect(page.locator('.react-flow__node')).toHaveCount(nodeCount);
});
