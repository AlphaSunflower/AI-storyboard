/**
 * 一次性诊断脚本：起一个 Vite dev server，用真实浏览器打开画布页面一次，
 * 观察 dev server 是否退出、页面是否报错。每一步都即时落到报告文件。用完即删。
 */
import { execFileSync, spawn } from 'node:child_process';
import { appendFileSync, writeFileSync } from 'node:fs';
import { chromium } from '@playwright/test';

const port = Number(process.argv[2] ?? 3000);
const url = `http://localhost:${port}/canvas/`;
const reportPath = 'repro-canvas-load.txt';
let serverLog = '';
let child = null;
let browser = null;

writeFileSync(reportPath, '');
const log = (line) => {
  appendFileSync(reportPath, `${line}\n`);
  console.log(line);
};

const cleanup = () => {
  if (child?.pid) {
    try {
      execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } catch { /* 进程可能已经退出 */ }
  }
  if (browser) void browser.close().catch(() => undefined);
};

const watchdog = setTimeout(() => {
  log('[watchdog] 240s 硬超时，准备退出');
  log(`[server alive] ${child?.exitCode === null}`);
  log(`[server log tail]\n${serverLog.slice(-3000)}`);
  cleanup();
  process.exit(2);
}, 240_000);

const waitFor = async (predicate, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return false;
};

log(`[start] url=${url} cwd=${process.cwd()}`);
child = spawn('npm', ['run', 'dev', '--', '--host', 'localhost', '--port', String(port)], {
  cwd: process.cwd(),
  shell: true,
  stdio: ['ignore', 'pipe', 'pipe'],
});
child.stdout.on('data', (chunk) => { serverLog += chunk.toString(); });
child.stderr.on('data', (chunk) => { serverLog += chunk.toString(); });
child.on('exit', (code, signal) => log(`[server exit event] code=${code} signal=${signal}`));

const ready = await waitFor(() => /ready in|Local:/.test(serverLog), 60_000);
log(`[server ready] ${ready}`);
if (!ready) {
  log(`[server log]\n${serverLog}`);
  cleanup();
  process.exit(1);
}

try {
  browser = await chromium.launch({ channel: 'msedge' });
  log('[browser launched]');
  const page = await browser.newPage();
  const events = [];
  page.on('console', (message) => events.push(`console.${message.type()}: ${message.text()}`));
  page.on('pageerror', (error) => events.push(`pageerror: ${error.message}`));
  page.on('requestfailed', (request) => events.push(`requestfailed: ${request.url()} ${request.failure()?.errorText}`));

  const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  log(`[goto] status=${response?.status()}`);
  for (let step = 1; step <= 5; step += 1) {
    await page.waitForTimeout(3_000);
    log(`[t+${step * 3}s] title=${await page.title()} rootChildren=${await page.locator('#root > *').count()} serverAlive=${child.exitCode === null}`);
  }
  log(`[body text] ${(await page.locator('body').innerText()).slice(0, 400).replace(/\n/g, ' | ')}`);
  await page.screenshot({ path: 'repro-canvas-load.png' });
  log('[screenshot] repro-canvas-load.png');
  log('[page events]');
  for (const event of events.slice(0, 40)) log(`  ${event}`);
} catch (error) {
  log(`[browser error] ${error?.message}`);
}

log(`[server alive after load] ${child.exitCode === null}`);
log(`[server exit code] ${child.exitCode}`);
log(`[server log tail]\n${serverLog.slice(-3000)}`);

clearTimeout(watchdog);
cleanup();
log('[done]');
process.exit(0);
