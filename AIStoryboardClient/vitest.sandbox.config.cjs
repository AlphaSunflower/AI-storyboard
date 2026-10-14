/**
 * 备用 vitest 配置（仅用于受限沙箱下的本地验证）。
 *
 * 为什么单独存在：默认的 `vitest.config.ts` 会让 vite 走 esbuild/rolldown 打包配置，
 * 打包过程中 vite 会执行 `net use`（`child_process.exec`，管道 stdio）。在受限沙箱里
 * 命名管道被禁止，该调用必然 `spawn EPERM`，测试无法启动。
 * 本文件是纯 CJS 普通对象：vite 不需要转译，也就不会触发那次 spawn。
 *
 * 测试语义与 `vitest.config.ts` 保持一致（jsdom + globals: false + 同一 include）。
 */
const path = require('node:path');

module.exports = {
  resolve: {
    alias: {
      '@': path.resolve(process.cwd(), './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['src/**/*.test.{ts,tsx}'],
    // 沙箱内 fork 子进程同样 EPERM；threads 池只用 worker_threads，不 spawn 进程。
    pool: 'threads',
    poolOptions: { threads: { singleThread: true } },
  },
};
