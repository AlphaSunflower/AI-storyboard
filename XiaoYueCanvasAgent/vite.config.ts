import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/canvas/',
  plugins: [react()],
  optimizeDeps: {
    exclude: ['@ffmpeg/ffmpeg', '@ffmpeg/core', '@ffmpeg/util'],
  },
  server: {
    port: 3000,
    proxy: {
      '/api/canvas': { target: 'http://localhost:8080', changeOrigin: true },
      '/api/auth': { target: 'http://localhost:8080', changeOrigin: true },
      '/api/files': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
