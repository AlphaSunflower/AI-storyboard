import { defineConfig } from '@playwright/test';

const port = Number(process.env.PLAYWRIGHT_PORT ?? 13000);

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
    reuseExistingServer: false,
  },
  // ffmpeg wasm 的重活用例（video-tools）单独一个 project：
  // 它跟几十个普通 spec 挤在同一个浏览器进程里跑时，wasm 加载会偶发超时；
  // 拆开后会用一个全新的浏览器会话跑它，互不干扰。
  projects: [
    {
      name: 'canvas',
      testIgnore: /video-tools\.spec\.ts/,
    },
    {
      name: 'heavy-media',
      testMatch: /video-tools\.spec\.ts/,
      timeout: 180_000,
    },
  ],
});
