import { defineConfig } from '@playwright/test';

const port = Number(process.env.PLAYWRIGHT_PORT ?? 3000);

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  use: {
    baseURL: `http://localhost:${port}`,
    browserName: 'chromium',
    channel: 'msedge',
    viewport: { width: 1440, height: 900 },
  },
  webServer: {
    command: `npm run dev -- --host localhost --port ${port}`,
    url: `http://localhost:${port}`,
    reuseExistingServer: true,
  },
});
