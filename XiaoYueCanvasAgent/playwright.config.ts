import { defineConfig } from '@playwright/test';

const port = Number(process.env.PLAYWRIGHT_PORT ?? 13000);
const bridgeToken = process.env.MORROW_BRIDGE_TOKEN ?? 'morrow-bridge-e2e-token';
const bridgePort = Number(process.env.MORROW_BRIDGE_PORT ?? 18787);

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
    env: {
      ...process.env,
      MORROW_BRIDGE_TOKEN: bridgeToken,
      MORROW_BRIDGE_PORT: String(bridgePort),
      VITE_BRIDGE_URL: process.env.VITE_BRIDGE_URL ?? `http://127.0.0.1:${bridgePort}`,
      VITE_BRIDGE_TOKEN: bridgeToken,
    },
  },
});
