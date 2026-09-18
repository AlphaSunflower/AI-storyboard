import { expect, type Page } from '@playwright/test';

export async function showAllNodes(page: Page) {
  const toggle = page.getByRole('button', { name: '展开全部节点' });
  await expect(toggle).toBeVisible();
  await toggle.click();
  await expect(page.getByRole('button', { name: '收起全部节点' })).toBeVisible();
}
