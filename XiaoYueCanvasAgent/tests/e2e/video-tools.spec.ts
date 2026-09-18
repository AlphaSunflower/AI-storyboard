import { expect, test } from '@playwright/test';
import { showAllNodes } from './node-display';

test('extracts a real tail frame with the local ffmpeg pipeline', async ({ page }, testInfo) => {
  test.setTimeout(90_000);
  const projectId = 'video-tools-project';
  const canvasId = 'video-tools-canvas';

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '视频工具测试' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === '/models' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/capabilities' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === `/canvases/${canvasId}/assets` && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/assets/upload' && request.method() === 'POST') {
      return route.fulfill({
        json: {
          id: 'server-frame',
          canvasId,
          type: 'image',
          mimeType: 'image/png',
          objectUrl: '/api/files/images/server-frame.png',
          producerNodeId: 'video-e2e',
          createdAt: new Date().toISOString(),
        },
      });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : {
            schemaVersion: 2,
            nodes: [{
              id: 'video-e2e',
              type: 'video',
              position: { x: 360, y: 220 },
              width: 420,
              height: 236,
              data: {
                kind: 'video',
                label: '测试视频',
                prompt: '',
                status: 'success',
                accent: '#8f9cff',
                assetId: 'video-e2e-asset',
              },
            }],
            edges: [],
            viewport: { x: 40, y: 40, zoom: 1 },
            generationRecords: [],
          };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const canvas = document.createElement('canvas');
    canvas.width = 96;
    canvas.height = 54;
    const context = canvas.getContext('2d')!;
    const stream = canvas.captureStream(12);
    const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8' });
    const chunks: Blob[] = [];
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
    const stopped = new Promise<void>((resolve) => { recorder.onstop = () => resolve(); });
    recorder.start(100);
    const startedAt = performance.now();
    await new Promise<void>((resolve) => {
      const draw = () => {
        const elapsed = performance.now() - startedAt;
        context.fillStyle = elapsed % 200 > 100 ? '#d7ff54' : '#8f9cff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#101817';
        context.font = '20px sans-serif';
        context.fillText(Math.floor(elapsed).toString(), 12, 34);
        if (elapsed >= 900) resolve();
        else requestAnimationFrame(draw);
      };
      draw();
    });
    recorder.stop();
    await stopped;
    stream.getTracks().forEach((track) => track.stop());
    const videoBlob = new Blob(chunks, { type: 'video/webm' });

    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
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
    const now = Date.now();
    const transaction = db.transaction(['projects', 'canvases', 'assetMeta', 'assetVariants'], 'readwrite');
    transaction.objectStore('projects').put({ schemaVersion: 2, id: projectId, title: '视频工具测试', canvases: [canvasId], createdAt: now, updatedAt: now });
    transaction.objectStore('assetMeta').put({
      id: 'video-e2e-asset',
      kind: 'video',
      name: 'synthetic.webm',
      mimeType: 'video/webm',
      bytes: videoBlob.size,
      width: 96,
      height: 54,
      durationSeconds: 1,
      projectId,
      canvasId,
      libraryState: 'project',
      createdAt: now,
    });
    for (const variant of ['original', 'thumbnail', 'preview']) {
      transaction.objectStore('assetVariants').put({ assetId: 'video-e2e-asset', variant, blob: videoBlob });
    }
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await showAllNodes(page);
  const videoNode = page.locator('.react-flow__node[data-id="video-e2e"]');
  await expect(videoNode).toBeVisible();
  await videoNode.click();
  await expect(page.getByTitle('提取帧')).toBeVisible();
  await page.getByTitle('提取帧').click();
  await page.getByRole('button', { name: '尾帧' }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(2, { timeout: 60_000 });
  await expect(page.locator('.react-flow__node').filter({ hasText: '测试视频 · 尾帧' })).toBeVisible();
  await expect(page.getByText('尾帧', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('video-tools-desktop.png'), fullPage: true });
});

test('reorders clips inside the video merge node', async ({ page }, testInfo) => {
  test.setTimeout(90_000);
  const projectId = 'video-merge-project';
  const canvasId = 'video-merge-canvas';
  const graph = {
    schemaVersion: 2,
    nodes: [
      { id: 'video-a', type: 'video', position: { x: 120, y: 120 }, data: { kind: 'video', label: '片段 1', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'video-a-asset' } },
      { id: 'video-b', type: 'video', position: { x: 120, y: 390 }, data: { kind: 'video', label: '片段 2', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'video-b-asset' } },
      { id: 'merge-e2e', type: 'videoMerge', position: { x: 620, y: 170 }, width: 560, height: 440, data: { kind: 'videoMerge', label: '视频合并', prompt: '', status: 'idle', accent: '#8f9cff' } },
    ],
    edges: [
      { id: 'edge-video-a', source: 'video-a', sourceHandle: 'video', target: 'merge-e2e', targetHandle: 'clips', type: 'canvas', data: { portOrder: 0 } },
      { id: 'edge-video-b', source: 'video-b', sourceHandle: 'video', target: 'merge-e2e', targetHandle: 'clips', type: 'canvas', data: { portOrder: 1 } },
    ],
    viewport: { x: 30, y: 30, zoom: 0.9 },
    generationRecords: [],
  };

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') return route.fulfill({ json: { id: projectId, title: '视频合并测试' } });
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    if (path === '/models' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/capabilities' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === `/canvases/${canvasId}/assets` && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/assets/upload' && request.method() === 'POST') {
      return route.fulfill({
        json: {
          id: 'server-merged',
          canvasId,
          type: 'video',
          mimeType: 'video/mp4',
          objectUrl: '/api/files/videos/server-merged.mp4',
          producerNodeId: 'merge-e2e',
          createdAt: new Date().toISOString(),
        },
      });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      return route.fulfill({
        json: {
          graph: request.method() === 'PUT' ? (request.postDataJSON() as { graph: unknown }).graph : graph,
          revision: 1,
        },
      });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
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
    const canvas = document.createElement('canvas');
    canvas.width = 96;
    canvas.height = 54;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#8f9cff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    const stream = canvas.captureStream(12);
    const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8' });
    const chunks: Blob[] = [];
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
    const stopped = new Promise<void>((resolve) => { recorder.onstop = () => resolve(); });
    recorder.start(100);
    const startedAt = performance.now();
    await new Promise<void>((resolve) => {
      const draw = () => {
        const elapsed = performance.now() - startedAt;
        context.fillStyle = elapsed % 160 > 80 ? '#d7ff54' : '#8f9cff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#101817';
        context.font = '20px sans-serif';
        context.fillText(Math.floor(elapsed).toString(), 12, 34);
        if (elapsed >= 1200) resolve();
        else requestAnimationFrame(draw);
      };
      draw();
    });
    recorder.stop();
    await stopped;
    stream.getTracks().forEach((track) => track.stop());
    const blob = new Blob(chunks, { type: 'video/webm' });
    const transaction = db.transaction(['projects', 'assetMeta', 'assetVariants'], 'readwrite');
    transaction.objectStore('projects').put({ schemaVersion: 2, id: projectId, title: '视频合并测试', canvases: [canvasId], createdAt: 1, updatedAt: 1 });
    for (const id of ['video-a-asset', 'video-b-asset']) {
      transaction.objectStore('assetMeta').put({ id, kind: 'video', name: `${id}.webm`, mimeType: 'video/webm', bytes: blob.size, width: 1280, height: 720, durationSeconds: 4, projectId, canvasId, libraryState: 'project', createdAt: 1 });
      for (const variant of ['original', 'thumbnail', 'preview']) {
        transaction.objectStore('assetVariants').put({ assetId: id, variant, blob });
      }
    }
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await showAllNodes(page);
  const mergeNode = page.locator('.react-flow__node[data-id="merge-e2e"]');
  await expect(mergeNode).toBeVisible();
  const clips = mergeNode.locator('.video-merge-list li');
  await expect(clips.nth(0)).toContainText('片段 1');
  await expect(clips.nth(1)).toContainText('片段 2');
  await expect(mergeNode.getByRole('button', { name: '合并视频' })).toBeEnabled();

  await mergeNode.getByLabel('上移 片段 2').click();
  await expect(clips.nth(0)).toContainText('片段 2');
  await expect(clips.nth(1)).toContainText('片段 1');
  await mergeNode.getByRole('button', { name: '合并视频' }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 60_000 });
  await expect(page.locator('.react-flow__node').filter({
    has: page.locator('input[aria-label="视频节点标题"][value="视频合并 · 合并结果"]'),
  })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('video-merge-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Fit View' }).click();
  await page.screenshot({ path: testInfo.outputPath('video-merge-mobile.png'), fullPage: true });
});

test('restores a 1.68x video by reprocessing its original source at 1.0x', async ({ page }) => {
  test.setTimeout(90_000);
  const projectId = 'video-speed-project';
  const canvasId = 'video-speed-canvas';
  let savedGraph: { nodes?: Array<{ data?: { assetId?: string; operation?: { type?: string; params?: Record<string, unknown> } } }> } | undefined;

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') return route.fulfill({ json: { id: projectId, title: '视频变速测试' } });
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    if (path === '/models' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/capabilities' && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === `/canvases/${canvasId}/assets` && request.method() === 'GET') return route.fulfill({ json: [] });
    if (path === '/assets/upload' && request.method() === 'POST') {
      return route.fulfill({
        json: {
          id: 'server-speed-restored',
          canvasId,
          type: 'video',
          mimeType: 'video/mp4',
          objectUrl: '/api/files/videos/server-speed-restored.mp4',
          producerNodeId: 'speed-node',
          createdAt: new Date().toISOString(),
        },
      });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      if (request.method() === 'PUT') {
        savedGraph = (request.postDataJSON() as { graph: typeof savedGraph }).graph;
      }
      return route.fulfill({
        json: {
          graph: request.method() === 'PUT'
            ? (request.postDataJSON() as { graph: unknown }).graph
            : {
                schemaVersion: 2,
                nodes: [{
                  id: 'speed-node',
                  type: 'video',
                  position: { x: 360, y: 220 },
                  width: 420,
                  height: 236,
                  data: {
                    kind: 'video',
                    label: '变速视频',
                    prompt: '',
                    status: 'success',
                    accent: '#8f9cff',
                    assetId: 'speed-adjusted',
                    operation: {
                      type: 'videoSpeed',
                      params: { factor: 1.68, sourceAssetId: 'speed-source', sourceDurationSeconds: 1.2 },
                    },
                  },
                }],
                edges: [],
                viewport: { x: 40, y: 40, zoom: 1 },
                generationRecords: [],
              },
          revision: 1,
        },
      });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const canvas = document.createElement('canvas');
    canvas.width = 96;
    canvas.height = 54;
    const context = canvas.getContext('2d')!;
    const stream = canvas.captureStream(12);
    const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8' });
    const chunks: Blob[] = [];
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
    const stopped = new Promise<void>((resolve) => { recorder.onstop = () => resolve(); });
    recorder.start(100);
    const startedAt = performance.now();
    await new Promise<void>((resolve) => {
      const draw = () => {
        const elapsed = performance.now() - startedAt;
        context.fillStyle = elapsed % 160 > 80 ? '#d7ff54' : '#8f9cff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        if (elapsed >= 1200) resolve();
        else requestAnimationFrame(draw);
      };
      draw();
    });
    recorder.stop();
    await stopped;
    stream.getTracks().forEach((track) => track.stop());
    const blob = new Blob(chunks, { type: 'video/webm' });

    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
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
    const transaction = db.transaction(['projects', 'assetMeta', 'assetVariants'], 'readwrite');
    transaction.objectStore('projects').put({ schemaVersion: 2, id: projectId, title: '视频变速测试', canvases: [canvasId], createdAt: 1, updatedAt: 1 });
    for (const id of ['speed-source', 'speed-adjusted']) {
      transaction.objectStore('assetMeta').put({
        id,
        kind: 'video',
        name: `${id}.webm`,
        mimeType: 'video/webm',
        bytes: blob.size,
        width: 96,
        height: 54,
        durationSeconds: 1.2,
        projectId,
        canvasId,
        libraryState: 'project',
        createdAt: 1,
        operation: id === 'speed-adjusted'
          ? { type: 'videoSpeed', params: { factor: 1.68, sourceAssetId: 'speed-source', sourceDurationSeconds: 1.2 } }
          : undefined,
      });
      for (const variant of ['original', 'thumbnail', 'preview']) {
        transaction.objectStore('assetVariants').put({ assetId: id, variant, blob });
      }
    }
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await showAllNodes(page);
  const videoNode = page.locator('.react-flow__node[data-id="speed-node"]');
  await videoNode.click();
  await page.getByTitle('变速').click();
  await page.getByLabel('倍数').fill('1');
  await page.getByRole('button', { name: '覆盖原视频' }).click();

  await expect.poll(() => savedGraph?.nodes?.[0]?.data?.assetId, { timeout: 60_000 }).toBe('server-speed-restored');
  expect(savedGraph?.nodes?.[0]?.data?.operation).toMatchObject({
    type: 'videoSpeed',
    params: { factor: 1, sourceAssetId: 'speed-source' },
  });
});
