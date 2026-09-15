import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('morrow-creative-canvas-v1', 4);
      request.onsuccess = () => {
        const db = request.result;
        const stores = Array.from(db.objectStoreNames);
        if (!stores.length) { db.close(); resolve(); return; }
        const transaction = db.transaction(stores, 'readwrite');
        stores.forEach((store) => transaction.objectStore(store).clear());
        transaction.oncomplete = () => { db.close(); resolve(); };
        transaction.onerror = () => reject(transaction.error);
      };
      request.onerror = () => reject(request.error);
    });
    localStorage.clear(); sessionStorage.clear();
  });
  await page.reload();
});

const png = async (page: import('@playwright/test').Page) => page.evaluate(async () => {
  const canvas = document.createElement('canvas'); canvas.width = 40; canvas.height = 40;
  const ctx = canvas.getContext('2d')!; ctx.fillStyle = '#ff775f'; ctx.fillRect(0, 0, 40, 40);
  ctx.fillStyle = '#d7ff54'; ctx.fillRect(4, 4, 12, 8);
  return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
});

test('imports assets into the project library and promotes to global', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();

  await page.locator('main.canvas-workspace input[type=file]').setInputFiles({ name: 'src.png', mimeType: 'image/png', buffer: Buffer.from(await png(page)) });
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  // 返回项目菜单 → 资产库 tab
  await page.getByRole('button', { name: '本地工作区菜单' }).click();
  await page.getByRole('menuitem', { name: '返回项目中心' }).click();
  await page.getByRole('button', { name: '资产库', exact: true }).click();
  await expect(page.getByAltText('src.png')).toBeVisible();
  await expect(page.getByRole('button', { name: '导入', exact: true })).toBeVisible();

  // 添加到全局库
  await page.getByRole('button', { name: '添加到总资产库' }).click();
  await page.getByRole('button', { name: '全局总资产库' }).click();
  await expect(page.getByAltText('src.png')).toBeVisible();
  await expect(page.getByText('跨项目可用')).toBeVisible();
});

test('canvas asset drawer lists assets and drops one onto the canvas', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();

  await page.locator('main.canvas-workspace input[type=file]').setInputFiles({ name: 'src.png', mimeType: 'image/png', buffer: Buffer.from(await png(page)) });
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  // 打开画布左侧资产库抽屉
  await page.getByRole('button', { name: '资产库' }).click();
  await expect(page.getByText('拖拽素材到画布上放置')).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  // 手动构造 HTML5 拖拽：抽屉卡片 → 画布
  await page.evaluate(() => {
    const source = document.querySelector('[draggable="true"]')!;
    const target = document.querySelector('.react-flow__pane')!;
    const rect = target.getBoundingClientRect();
    const dataTransfer = new DataTransfer();
    source.dispatchEvent(new DragEvent('dragstart', { dataTransfer, bubbles: true }));
    target.dispatchEvent(new DragEvent('drop', { dataTransfer, bubbles: true, clientX: rect.x + rect.width * 0.7, clientY: rect.y + rect.height * 0.6 }));
  });
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
});
