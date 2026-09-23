import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: process.env.VITE_API_PROXY_TARGET
      ? {
          '/api/agent': {
            target: process.env.VITE_API_PROXY_TARGET,
            changeOrigin: true,
            configure: (proxy) => {
              if (!process.env.VITE_DEV_USER_ID) return;
              proxy.on('proxyReq', (proxyReq) => {
                proxyReq.setHeader('X-User-Id', process.env.VITE_DEV_USER_ID!);
              });
            },
          },
          '/api': {
            target: 'http://localhost:8080',
            changeOrigin: true,
          },
        }
      : {
          '/api': {
            target: 'http://localhost:8080',
            changeOrigin: true,
          },
        },
  },
})
