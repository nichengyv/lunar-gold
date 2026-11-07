import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 开发服务器通过 proxy 转发 /api 到 Spring Boot，避免跨域；
// 生产环境由 Nginx 托管 dist 并反代 /api（见 deploy/nginx.conf）。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
});
