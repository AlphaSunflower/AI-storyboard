import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 节点面板回归：
 * - 除文字节点外，节点框内只剩预览面，操作控件全部挂到框下的浮动面板（.react-flow__node-toolbar）；
 * - 面板跟随选中态出现（与生成节点的 composer 一致）；
 * - 分镜拆解可以正常编辑并看见文字（曾经因为继承到浅色纸样式而「能打字看不见字」）；
 * - 分镜拆解 / 镜头 / 视频合并 只有一层面板（外层卡片不画第二层框）；
 * - 视频合并是中性配色，且合并动作单独成块。
 */
const panelOf = (page: import('@playwright/test').Page, id: string) =>
  page.locator(`.react-flow__node-toolbar[data-id="${id}"]`);

test('input, planner, shot, and merge nodes share one panel surface', async ({ page }) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  const nodes = [
    // 必须单排铺开：浮动面板挂在节点下方且约 418 流单位高，两排布局无论取什么缩放
    // 都无法同时满足「顶排避开顶部项目栏」和「底排面板落进 900px 视口」（推导见下）。
    // 之前是 2×2，底排面板一直挂在视口外，只靠 Playwright 自动滚动才点得到，
    // 满载时滚动失败就 30s 超时 —— 这是该用例偶发失败的根因。
    //
    // 约束：面板底 ≈ (bottomY + nodeH + 418)·z ≤ 876（视口 900 减去边距）
    //       顶排标题 ≥ topY·z ≥ 90（避开项目栏）
    //       两排还需 bottomY ≥ topY + nodeH + 418 不重叠
    //       → 90/z ≤ topY 且 bottomY ≤ 876/z − 938，代入不重叠式得 z ≤ 0.41，且 topY 仅剩 ~20 流单位的窗口。
    { id: 'panel-image', type: 'image', position: { x: 260, y: 300 }, width: 460, height: 420, data: { kind: 'image', label: '图片', status: 'idle' } },
    { id: 'panel-planner', type: 'storyboardPlanner', position: { x: 840, y: 300 }, width: 520, height: 520, data: { kind: 'storyboardPlanner', label: '分镜拆解', prompt: '', status: 'idle' } },
    { id: 'panel-shot', type: 'shot', position: { x: 1480, y: 300 }, width: 520, height: 480, data: { kind: 'shot', label: '镜头 01', shotOrdinal: 1, status: 'idle' } },
    { id: 'panel-merge', type: 'videoMerge', position: { x: 2120, y: 300 }, width: 520, height: 480, data: { kind: 'videoMerge', label: '视频合并', status: 'idle' } },
  ];
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'panel-project', title: '面板测试' }] });
    if (path === '/projects/panel-project') return route.fulfill({ json: { id: 'panel-project', title: '面板测试' } });
    if (path === '/projects/panel-project/canvases') return route.fulfill({ json: [{ id: 'panel-canvas', projectId: 'panel-project', title: '默认画布' }] });
    if (path === '/canvases/panel-canvas/graph') {
      const graph = { schemaVersion: 2, nodes, edges: [], viewport: { x: 0, y: 0, zoom: .5 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    return route.fulfill({ json: {} });
  });
  await page.goto('/?token=test-token&userId=test-user');
  await page.evaluate(async () => {
    const now = Date.now();
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const name of ['legacyProjects', 'canvases', 'assets', 'assetMeta', 'assetVariants', 'generationQueue']) {
        if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, { keyPath: name === 'assetVariants' ? ['assetId', 'variant'] : 'id' });
      }
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = db.transaction(['legacyProjects', 'canvases'], 'readwrite');
    transaction.objectStore('legacyProjects').put({ schemaVersion: 2, id: 'panel-project', title: '面板测试', canvases: ['panel-canvas'], createdAt: now, updatedAt: now });
    transaction.objectStore('canvases').put({ schemaVersion: 1, id: 'panel-canvas', projectId: 'panel-project', title: '默认画布', nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: .5 }, createdAt: now, updatedAt: now });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=panel-project&canvas=panel-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.getByRole('button', { name: '展开全部节点' }).click();
  // 收起右侧 Moon 面板：这排节点铺得比视口宽，最右边那个节点的标题正好落在面板底下，
  // 不收起就只能靠浏览器把画布容器偷偷滚一下才点得到（那不是我们要测的东西）。
  await page.getByRole('button', { name: '收起面板' }).click();
  await expect(page.locator('.app-shell')).toHaveClass(/side-is-closed/);

  // 图片节点：上传组件挂在节点框下面板里，选中节点即可拿到
  const imageNode = page.locator('.react-flow__node[data-id="panel-image"]');
  await imageNode.locator('.ai-node-external-title').click();
  await expect(panelOf(page, 'panel-image').getByRole('button', { name: /上传图片/ })).toHaveCount(1);
  // 节点框内不再放操作控件，只有预览面
  await expect(imageNode.locator('.node-surface')).toHaveCount(1);
  await expect(imageNode.getByRole('button', { name: /上传图片/ })).toHaveCount(0);

  // 分镜拆解：文字可编辑且看得见
  await page.locator('.react-flow__node[data-id="panel-planner"]').click();
  const plannerPanel = panelOf(page, 'panel-planner');
  const editable = plannerPanel.locator('.rt-input__editable');
  await expect(editable).toBeVisible();
  const color = await editable.evaluate((element) => getComputedStyle(element).color);
  expect(color).toBe('rgb(244, 244, 245)');
  await editable.click();
  await page.keyboard.type('调试剧本');
  await expect(editable).toHaveText('调试剧本');

  // 分镜拆解 / 镜头 / 视频合并：外层卡片不再画第二层框，由内层视图承担面板
  for (const id of ['panel-planner', 'panel-shot', 'panel-merge']) {
    const card = page.locator(`.react-flow__node[data-id="${id}"] .node-card`);
    const border = await card.evaluate((element) => getComputedStyle(element).borderTopWidth);
    expect(border).toBe('0px');
  }

  // 视频合并：中性面板 + 合并动作独立成块
  // 点标题选中节点（节点框下方可能被别的面板遮住，标题永远在节点顶部）
  await page.locator('.react-flow__node[data-id="panel-merge"] .ai-node-external-title').click();
  const mergePanel = panelOf(page, 'panel-merge').locator('.node-float-panel');
  // 玻璃态允许白色高光渐变；这里守的是「中性」——渐变里每个色值的通道极差 ≤ 2（旧主题的绿会被拦下）。
  const panelBackground = await mergePanel.evaluate((element) => getComputedStyle(element).backgroundImage);
  const channels = [...panelBackground.matchAll(/rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})/g)]
    .map((match) => [Number(match[1]), Number(match[2]), Number(match[3])]);
  for (const [r, g, b] of channels) {
    expect(Math.max(r, g, b) - Math.min(r, g, b), `面板渐变出现色相：${panelBackground}`).toBeLessThanOrEqual(2);
  }
  const action = mergePanel.locator('.video-merge-footer__action');
  await expect(action).toHaveCount(1);
  await expect(action.getByRole('button', { name: '合并视频' })).toBeVisible();
  // 节点框内只留预览面，合并列表挂在面板里
  await expect(page.locator('.react-flow__node[data-id="panel-merge"] .node-surface')).toHaveCount(1);
  await page.screenshot({ path: 'test-results/node-panels.png' });
});
