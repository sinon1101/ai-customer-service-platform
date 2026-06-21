import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 后端运行在 8081,无 context-path、未配 CORS。
// dev 阶段用 Vite 代理把 /api、/ws 转发到后端,前端就不用面对跨域。
//   /api/auth/login  →  http://localhost:8081/auth/login   (剥掉 /api 前缀)
//   /ws/chat         →  ws://localhost:8081/ws/chat         (WebSocket 升级)
const BACKEND = 'http://localhost:8081'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      '/ws': {
        target: BACKEND,
        ws: true,
        changeOrigin: true
      }
    }
  }
})
